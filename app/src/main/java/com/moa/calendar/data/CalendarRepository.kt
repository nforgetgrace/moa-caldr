package com.moa.calendar.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class CalendarRepository(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("moa_calendar", Context.MODE_PRIVATE)
    private val device = DeviceCalendars(app)
    private val vault = CredentialVault(app)

    init {
        // Upgrade old installations without touching any real calendar or credential.
        if (prefs.contains("demo") || prefs.contains("demo_events")) {
            prefs.edit().remove("demo").remove("demo_events")
                .putStringSet("hidden", hiddenCalendars().filterNot { it.startsWith("demo:") }.toSet()).apply()
        }
    }

    fun selectedGoogleAccount(): String? = prefs.getString("google_account", null)
    fun selectGoogleAccount(account: String?) {
        require(account == null || account.isNotBlank())
        prefs.edit().apply { if (account == null) remove("google_account") else putString("google_account", account) }.apply()
    }
    fun naverConnected(): Boolean = vault.exists()
    fun naverAccount(): String = prefs.getString("naver_account", "").orEmpty()
    fun hiddenCalendars(): Set<String> = prefs.getStringSet("hidden", emptySet()).orEmpty().toSet()
    fun setVisible(id: String, visible: Boolean) {
        val hidden = hiddenCalendars().toMutableSet()
        if (visible) hidden.remove(id) else hidden.add(id)
        prefs.edit().putStringSet("hidden", hidden).apply()
    }

    suspend fun load(from: Long, to: Long, refreshRemote: Boolean = true): CalendarSnapshot = withContext(Dispatchers.IO) {
        if (refreshRemote) lock.withLock {
            if (naverConnected()) {
                try {
                    val credentials = vault.read()
                    val client = CalDavClient(credentials.server, credentials.username, credentials.password)
                    val calendars = client.discover()
                    val resources = calendars.associateWith { client.fetch(it, from, to) }
                    resources.forEach { (calendar, data) -> data.forEach { IcsCodec.parse(it, calendar, from, to) } }
                    storeRemote(calendars, resources, fetchTasks(client, calendars))
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    prefs.edit().putString("last_sync_error", (e.message ?: "네이버 연결을 확인해 주세요.") + " 저장된 일정을 표시합니다.").apply()
                }
            }
        }
        // Readers never wait for network I/O. Capture one committed preferences generation.
        val state = prefs.all
        val remote = JSONObject(state["remote"] as? String ?: "{}")
        val remoteTasks = JSONObject(state["remote_tasks"] as? String ?: "{}")
        val errors = mutableListOf<String>()
        if (naverConnected()) (state["last_sync_error"] as? String)?.let { errors += it }
        val account = state["google_account"] as? String
        val allDeviceCalendars = runCatching { device.calendars() }.getOrElse { errors += "기기 캘린더를 읽지 못했어요. 권한을 확인해 주세요."; emptyList() }
        val deviceCalendars = calendarsForGoogleAccount(allDeviceCalendars, account)
        val deviceEvents = runCatching { device.events(deviceCalendars, from, to) }.getOrElse { errors += "기기 일정을 읽지 못했어요. 권한을 확인해 주세요."; emptyList() }
        val remoteCalendars = readCalendars(remote)
        val remoteEvents = mutableListOf<CalendarEvent>()
        for (calendar in remoteCalendars) {
            try { readResources(calendar.id, remote).forEach { remoteEvents += IcsCodec.parse(it, calendar, from, to) } }
            catch (e: Exception) { errors += "${calendar.name}: ${e.message ?: "일정을 읽지 못했어요."}" }
        }
        val tasks = remoteCalendars.filter { it.supportsTasks }.flatMap { calendar ->
            runCatching { readTaskResources(calendar.id, remoteTasks).flatMap { TaskCodec.parse(it, calendar) } }
                .getOrElse { errors += "저장된 할 일을 읽지 못했어요."; emptyList() }
        }
        val datedTasks = tasks.mapNotNull { it.asCalendarEvent() }.filter { it.startMillis < to && it.endMillis > from }
        val taskNotice = when {
            !naverConnected() -> "네이버 계정을 연결하면 서버가 제공하는 할 일을 확인할 수 있어요."
            state["tasks_error"] is String -> state["tasks_error"].toString() + " 이전에 받은 할 일은 유지합니다."
            state["tasks_checked"] != true -> "할 일 지원 여부를 확인하려면 새로고침해 주세요."
            remoteCalendars.none { it.supportsTasks } -> "이 계정의 CalDAV 서버가 할 일 목록을 제공하지 않습니다. 네이버 웹의 ‘내 할 일’은 원본에서 확인해 주세요."
            tasks.isEmpty() -> "CalDAV 서버에서 전달된 할 일이 없어요. 웹의 ‘내 할 일’이 CalDAV에 공개되지 않을 수 있습니다."
            else -> "네이버에서 받은 할 일 ${tasks.size}개 · 완료와 수정은 원본에서 관리해 주세요."
        }
        CalendarSnapshot(deviceCalendars + remoteCalendars, (deviceEvents + remoteEvents + datedTasks).sortedBy { it.startMillis },
            state["last_sync"] as? Long ?: 0, errors, allDeviceCalendars.filter { it.source == CalendarSource.GOOGLE }.map { it.account }.distinct().sorted(),
            tasks, taskNotice, runCatching { device.googleSyncNotice(account) }.getOrElse { "기기 계정 동기화 상태를 확인할 수 없어요." })
    }

    suspend fun connectNaver(username: String, password: String, server: String = "https://caldav.calendar.naver.com/") = withContext(Dispatchers.IO) {
        lock.withLock {
            val credentials = NaverCredentials(server.trim(), username.trim().removeSuffix("@naver.com"), password)
            val client = CalDavClient(credentials.server, credentials.username, credentials.password)
            val calendars = client.discover()
            val today = LocalDate.now()
            val from = today.minusMonths(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val to = today.plusMonths(3).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val resources = calendars.associateWith { client.fetch(it, from, to) }
            resources.forEach { (c, data) -> data.forEach { IcsCodec.parse(it, c, from, to) } }
            vault.save(credentials)
            storeRemote(calendars, resources, fetchTasks(client, calendars), credentials.username)
        }
    }

    suspend fun disconnectNaver() = withContext(Dispatchers.IO) {
        lock.withLock {
            vault.clear()
            prefs.edit().remove("naver_account").remove("remote").remove("last_sync").remove("last_sync_error").remove("remote_tasks").remove("tasks_error").remove("tasks_checked").commit()
        }
    }

    suspend fun save(draft: EventDraft, existing: CalendarEvent? = null) = withContext(Dispatchers.IO) {
        lock.withLock {
            validateDraft(draft)
            require(existing?.task != true) { "할 일 변경은 원본에서 해 주세요." }
            require(existing == null || existing.calendarId == draft.calendarId) { "일정의 원본 캘린더는 변경할 수 없어요." }
            when {
                draft.calendarId.startsWith("device:") -> device.save(draft, existing)
                else -> {
                    val calendar = readCalendars().firstOrNull { it.id == draft.calendarId } ?: error("캘린더를 찾지 못했어요.")
                    require(calendar.supportsEvents) { "할 일 전용 목록에는 일정을 저장할 수 없어요." }
                    require(calendar.writable) { "읽기 전용 캘린더예요." }
                    val credentials = vault.read()
                    val client = CalDavClient(credentials.server, credentials.username, credentials.password)
                    val uid = UUID.randomUUID().toString()
                    val href = existing?.href ?: "${calendar.id.trimEnd('/')}/$uid.ics"
                    val ics = IcsCodec.write(draft, existing, uid)
                    val etag = client.put(href, ics, existing?.etag)
                    val resources = readResources(calendar.id).filterNot { it.href == href } + DavResource(href, etag, ics)
                    updateResourceCache(calendar.id, resources)
                }
            }
        }
    }

    suspend fun delete(event: CalendarEvent) = withContext(Dispatchers.IO) {
        lock.withLock {
            require(!event.task) { "할 일 변경은 원본에서 해 주세요." }
            require(!event.recurring) { "반복 일정은 원본 캘린더에서 삭제해 주세요." }
            when (event.source) {
                CalendarSource.GOOGLE, CalendarSource.DEVICE -> device.delete(event)
                CalendarSource.NAVER -> {
                    check(readCalendars().any { it.id == event.calendarId && it.writable }) { "읽기 전용 캘린더예요." }
                    require(!event.rawIcs.contains("ATTENDEE", true) && !event.rawIcs.contains("ORGANIZER", true)) { "초대 일정은 원본 캘린더에서 삭제해 주세요." }
                    val credentials = vault.read()
                    CalDavClient(credentials.server, credentials.username, credentials.password).delete(event.href, event.etag)
                    updateResourceCache(event.calendarId, readResources(event.calendarId).filterNot { it.href == event.href })
                }
            }
        }
    }

    suspend fun enableGoogleCalendar(id: String) = withContext(Dispatchers.IO) {
        device.enableGoogleCalendarSync(id, selectedGoogleAccount())
    }

    suspend fun requestGoogleSync() = withContext(Dispatchers.IO) {
        val account = selectedGoogleAccount() ?: error("먼저 Google 계정을 선택해 주세요.")
        device.requestGoogleSync(account)
    }

    private fun readTaskResources(id: String, tasks: JSONObject): List<DavResource> {
        val array = tasks.optJSONArray(id) ?: JSONArray()
        return (0 until array.length()).map { array.getJSONObject(it).let { o -> DavResource(o.getString("href"), o.optString("etag"), o.getString("ics")) } }
    }

    private fun fetchTasks(client: CalDavClient, calendars: List<CalendarInfo>): Result<Map<CalendarInfo, List<DavResource>>> {
        return try {
            val resources = calendars.filter { it.supportsTasks }.associateWith { client.fetchTasks(it) }
            resources.forEach { (calendar, data) -> data.forEach { TaskCodec.parse(it, calendar) } }
            Result.success(resources)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Result.failure(e)
        }
    }

    private fun readCalendars(remote: JSONObject = JSONObject(prefs.getString("remote", "{}")!!)): List<CalendarInfo> {
        val array = remote.optJSONArray("calendars") ?: JSONArray()
        return (0 until array.length()).map { i -> array.getJSONObject(i).let {
            CalendarInfo(it.getString("id"), it.getString("name"), it.getString("account"), CalendarSource.NAVER, CalendarSource.NAVER.displayColor(it.getInt("color")), it.optBoolean("writable"),
                supportsEvents = it.optBoolean("events", true), supportsTasks = it.optBoolean("tasks", false))
        } }
    }
    private fun readResources(id: String, remote: JSONObject = JSONObject(prefs.getString("remote", "{}")!!)): List<DavResource> {
        val array = remote.optJSONObject("resources")?.optJSONArray(id) ?: JSONArray()
        return (0 until array.length()).map { array.getJSONObject(it).let { o -> DavResource(o.getString("href"), o.optString("etag"), o.getString("ics")) } }
    }
    private fun resourceJson(resources: List<DavResource>) = JSONArray().apply {
        resources.forEach { put(JSONObject().put("href", it.href).put("etag", it.etag).put("ics", it.ics)) }
    }
    private fun storeRemote(calendars: List<CalendarInfo>, resources: Map<CalendarInfo, List<DavResource>>,
        tasks: Result<Map<CalendarInfo, List<DavResource>>>, account: String? = null) {
        val json = JSONObject().put("calendars", JSONArray().apply { calendars.forEach {
            put(JSONObject().put("id", it.id).put("name", it.name).put("account", it.account).put("color", it.color).put("writable", it.writable).put("events", it.supportsEvents).put("tasks", it.supportsTasks))
        } }).put("resources", JSONObject().apply { resources.forEach { (calendar, data) -> put(calendar.id, resourceJson(data)) } })
        val editor = prefs.edit().putString("remote", json.toString())
            .putLong("last_sync", System.currentTimeMillis()).remove("last_sync_error")
        if (account != null) editor.putString("naver_account", account)
        tasks.fold(onSuccess = { data ->
            val taskJson = JSONObject().apply { data.forEach { (calendar, items) -> put(calendar.id, resourceJson(items)) } }
            editor.putString("remote_tasks", taskJson.toString()).putBoolean("tasks_checked", true).remove("tasks_error")
        }, onFailure = { error ->
            editor.putString("tasks_error", "할 일 조회를 완료하지 못했어요. " + (error.message ?: "서버 지원 여부를 확인해 주세요."))
        })
        // Publish events, tasks and sync status together, preserving old tasks on task-only failure.
        check(editor.commit()) { "일정 캐시를 저장하지 못했어요." }
    }
    private fun updateResourceCache(id: String, resources: List<DavResource>) {
        val json = JSONObject(prefs.getString("remote", "{}")!!)
        val all = json.optJSONObject("resources") ?: JSONObject()
        all.put(id, resourceJson(resources))
        json.put("resources", all)
        prefs.edit().putString("remote", json.toString()).commit()
    }

    companion object {
        private val lock = Mutex()
    }
}

private data class NaverCredentials(val server: String, val username: String, val password: String)

private class CredentialVault(context: Context) {
    private val prefs = context.getSharedPreferences("moa_vault", Context.MODE_PRIVATE)
    fun exists() = prefs.contains("encrypted")
    fun clear() { check(prefs.edit().clear().commit()) }
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("moa_naver", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("moa_naver", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun save(value: NaverCredentials) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val json = JSONObject().put("server", value.server).put("username", value.username).put("password", value.password).toString()
        val encrypted = cipher.doFinal(json.toByteArray(Charsets.UTF_8))
        check(prefs.edit().putString("encrypted", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP)).commit())
    }
    fun read(): NaverCredentials {
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(prefs.getString("iv", ""), Base64.NO_WRAP)))
            }
            val text = cipher.doFinal(Base64.decode(prefs.getString("encrypted", ""), Base64.NO_WRAP)).toString(Charsets.UTF_8)
            val json = JSONObject(text)
            return NaverCredentials(json.getString("server"), json.getString("username"), json.getString("password"))
        } catch (_: Exception) { error("저장된 로그인 정보를 열 수 없어요. 네이버 계정을 다시 연결해 주세요.") }
    }
}
