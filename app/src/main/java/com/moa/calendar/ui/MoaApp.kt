package com.moa.calendar.ui

import android.Manifest
import android.accounts.Account
import android.accounts.AccountManager
import android.accounts.OperationCanceledException
import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moa.calendar.data.*
import com.moa.calendar.widget.AgendaWidget
import com.moa.calendar.widget.MonthWidget
import com.moa.calendar.widget.WidgetUpdater
import com.moa.calendar.widget.CalendarSyncJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable fun MoaApp(externalRevision: Int, resumed: Boolean, initialDate: String?, widgetOpenRevision: Int, onPermissionChanged: () -> Unit) {
    val context = LocalContext.current
    val repository = remember { CalendarRepository(context) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var selectedString by rememberSaveable { mutableStateOf(initialDate ?: LocalDate.now().toString()) }
    val selected = runCatching { LocalDate.parse(selectedString) }.getOrDefault(LocalDate.now())
    var monthString by rememberSaveable { mutableStateOf(YearMonth.from(selected).toString()) }
    val month = YearMonth.parse(monthString)
    var page by rememberSaveable { mutableIntStateOf(0) }
    var mode by rememberSaveable { mutableIntStateOf(0) }
    var revision by remember { mutableIntStateOf(0) }
    var snapshot by remember { mutableStateOf(CalendarSnapshot()) }
    var hidden by remember { mutableStateOf(repository.hiddenCalendars()) }
    var loading by remember { mutableStateOf(false) }
    var cacheLoaded by remember { mutableStateOf(false) }
    val naverSyncing by CalendarRepository.naverSyncing.collectAsState()
    var refreshGeneration by remember { mutableIntStateOf(0) }
    var showEditor by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<CalendarEvent?>(null) }
    var taskView by rememberSaveable { mutableStateOf(false) }
    var selectedTask by remember { mutableStateOf<CalendarTask?>(null) }
    var showGoogle by remember { mutableStateOf(false) }
    var showGoogleConnect by rememberSaveable { mutableStateOf(false) }
    var colorCalendar by remember { mutableStateOf<CalendarInfo?>(null) }
    var addingGoogle by remember { mutableStateOf(false) }
    var googleConnectError by remember { mutableStateOf("") }
    var selectedGoogleAccount by remember { mutableStateOf(repository.selectedGoogleAccount()) }
    var hasCalendarPermission by remember { mutableStateOf(DeviceCalendars(context).hasReadPermission()) }
    val googleSyncAccounts = remember(selectedGoogleAccount, snapshot.calendars) {
        selectedGoogleAccount?.let { listOf(it) }
            ?: snapshot.calendars.filter { it.source == CalendarSource.GOOGLE }.map { it.account }
    }
    val googleSyncing = rememberGoogleSyncActive(resumed, googleSyncAccounts, externalRevision)
    val syncing = loading || naverSyncing || googleSyncing
    var showNaver by remember { mutableStateOf(false) }
    var search by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    val events = snapshot.events.filter { it.calendarId !in hidden }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.READ_CALENDAR] == true) {
            hasCalendarPermission = true; onPermissionChanged(); revision++; showGoogle = true
        } else scope.launch { snackbar.showSnackbar("캘린더 권한이 있어야 Google 일정을 함께 볼 수 있어요.") }
    }
    fun select(date: LocalDate) { selectedString = date.toString(); monthString = YearMonth.from(date).toString() }
    LaunchedEffect(widgetOpenRevision) {
        // Initial selection comes from rememberSaveable; only a new widget click overrides it.
        if (widgetOpenRevision == 0) return@LaunchedEffect
        val date = initialDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return@LaunchedEffect
        select(date)
        page = 0
        showEditor = false
        selectedTask = null
        searching = false
        search = ""
    }
    fun message(text: String) { scope.launch { snackbar.showSnackbar(text) } }
    fun refreshManually() { if (!syncing) { revision++ } }
    fun addEvent() {
        if (snapshot.calendars.none { it.writable && it.syncEnabled && it.supportsEvents }) { page = 3; message("먼저 일정을 저장할 캘린더를 연결해 주세요.") }
        else { editing = null; showEditor = true }
    }
    fun openEvent(event: CalendarEvent) {
        if (event.task) selectedTask = snapshot.tasks.firstOrNull { it.id == event.id }
        else { editing = event; showEditor = true }
    }
    fun requestGoogleSync() {
        scope.launch {
            try { repository.requestGoogleSync(); message("선택한 계정에 동기화를 요청했어요. 도착하는 일정은 자동 반영됩니다.") }
            catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; message(e.message ?: "기기 동기화 설정을 확인해 주세요.") }
            revision++
        }
    }
    fun selectGoogle(account: String?) {
        repository.selectGoogleAccount(account); selectedGoogleAccount = account
        snapshot = snapshot.forGoogleAccount(account)
        revision++; showGoogle = false; showGoogleConnect = false
        if (account != null) requestGoogleSync()
    }
    val accountLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val name = result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
        val type = result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_TYPE)
        if (result.resultCode == Activity.RESULT_OK && type == "com.google" && !name.isNullOrBlank()) {
            selectGoogle(name)
        }
    }
    val now = LocalDate.now()
    val zone = ZoneId.systemDefault()
    val from = minOf(month.atDay(1).minusMonths(1), now.withDayOfMonth(1).minusMonths(1)).atStartOfDay(zone).toInstant().toEpochMilli()
    val to = maxOf(month.atEndOfMonth().plusMonths(2), now.plusMonths(3)).atStartOfDay(zone).toInstant().toEpochMilli()
    val naverConnected = repository.naverConnected()
    // Provider/cache notifications only reread local data; they never trigger another network sync.
    LaunchedEffect(monthString, externalRevision) {
        selectedGoogleAccount = repository.selectedGoogleAccount()
        hasCalendarPermission = DeviceCalendars(context).hasReadPermission()
        try {
            snapshot = repository.applyDisplayColors(repository.load(from, to, false))
            cacheLoaded = true
            WidgetUpdater.update(context)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            message(e.message ?: "일정을 불러오지 못했어요.")
        }
    }
    LaunchedEffect(monthString, revision, resumed) {
        if (!resumed) return@LaunchedEffect
        val generation = ++refreshGeneration
        loading = true
        try {
            // Show committed local data before starting any remote request.
            if (!cacheLoaded) {
                snapshot = repository.applyDisplayColors(repository.load(from, to, false))
                cacheLoaded = true
            }
            snapshot = repository.applyDisplayColors(repository.load(from, to, true))
            WidgetUpdater.update(context)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            message(e.message ?: "일정을 불러오지 못했어요.")
        } finally {
            if (generation == refreshGeneration) { loading = false }
        }
    }
    val currentSyncing by rememberUpdatedState(syncing)
    LaunchedEffect(resumed, naverConnected) {
        if (resumed && naverConnected) while (true) { delay(60_000); if (!currentSyncing) revision++ }
    }

    Scaffold(
        containerColor = CanvasColor,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Column(Modifier.background(Color.White).navigationBarsPadding()) {
                HorizontalDivider(color = LineColor)
                Row(Modifier.fillMaxWidth().height(74.dp).padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    listOf("캘린더" to "calendar", "일정" to "agenda", "위젯" to "widget", "설정" to "settings").forEachIndexed { index, (title, icon) ->
                        Column(Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).clickable { page = index }.padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.clip(RoundedCornerShape(12.dp)).background(if (page == index) AccentWash else Color.Transparent).padding(horizontal = 17.dp, vertical = 5.dp)) {
                                LineIcon(icon, if (page == index) Accent else Muted)
                            }
                            Text(title, fontSize = 10.sp, color = if (page == index) Accent else Muted, fontWeight = if (page == index) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                }
            }
        },
        floatingActionButton = {
            if (page < 2 && !(page == 1 && taskView)) ExtendedFloatingActionButton(onClick = ::addEvent, containerColor = Accent, contentColor = Color.White,
                modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = "일정 추가" },
                shape = RoundedCornerShape(20.dp), elevation = FloatingActionButtonDefaults.elevation(3.dp),
                icon = { LineIcon("plus", Color.White, Modifier.size(18.dp)) }, text = { Text("일정 추가") })
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 640.dp).fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    BrandMark()
                    Spacer(Modifier.width(9.dp))
                    Text("moa", fontSize = 29.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1.5).sp, color = Ink)
                    Spacer(Modifier.width(9.dp))
                    Text("일상을 한곳에", fontSize = 11.sp, color = Muted)
                    Spacer(Modifier.weight(1f))
                    SyncRefreshAction(syncing, ::refreshManually)
                    IconButton(onClick = { searching = !searching; page = 1 }, Modifier.size(44.dp)) { LineIcon("search", label = "일정 검색") }
                }
                if (snapshot.errors.isNotEmpty()) {
                    Surface(color = Color(0xFFFFF0E8), modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                        Text(snapshot.errors.joinToString("\n"), Modifier.padding(12.dp), fontSize = 12.sp, color = Color(0xFF8B4C2E))
                    }
                }
                if (searching && page == 1) OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                    placeholder = { Text("제목, 장소, 메모 검색") }, singleLine = true, shape = RoundedCornerShape(16.dp),
                    trailingIcon = { IconButton(onClick = { search = ""; searching = false }) { LineIcon("close", label = "검색 닫기") } })
                if (!cacheLoaded) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("저장된 일정을 불러오는 중이에요", fontSize = 13.sp, color = Muted)
                } else when (page) {
                    0 -> CalendarPage(month, selected, events, snapshot, hidden, mode,
                        { mode = it; if (it == 2) page = 1 }, ::select,
                        { val next = month.plusMonths(it); select(next.atDay(selected.dayOfMonth.coerceAtMost(next.lengthOfMonth()))) },
                        { select(LocalDate.now()) },
                        { id -> val visible = id in hidden; repository.setVisible(id, visible); hidden = repository.hiddenCalendars(); scope.launch { WidgetUpdater.update(context) } },
                        ::openEvent, { page = 3 }, ::addEvent)
                    1 -> Column(Modifier.fillMaxSize()) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(!taskView, { taskView = false }, label = { Text("일정") })
                            FilterChip(taskView, { taskView = true }, label = { Text("할 일") })
                        }
                        if (taskView) TasksPage(snapshot.tasks.filter { it.calendarId !in hidden }, snapshot.calendars, snapshot.taskNotice, search, { selectedTask = it })
                        else AgendaPage(events, snapshot.calendars, selected, search, { select(it) }, ::openEvent)
                    }
                    2 -> WidgetPage(events) { agenda ->
                        val manager = AppWidgetManager.getInstance(context)
                        val component = ComponentName(context, if (agenda) AgendaWidget::class.java else MonthWidget::class.java)
                        if (manager.isRequestPinAppWidgetSupported) {
                            if (!manager.requestPinAppWidget(component, null, null)) message("홈 화면을 길게 눌러 모아 위젯을 추가해 주세요.")
                        } else message("홈 화면을 길게 누른 뒤 위젯 → 모아 캘린더에서 추가해 주세요.")
                    }
                    3 -> SettingsPage(snapshot, naverConnected, repository.naverAccount(), hidden, selectedGoogleAccount, hasCalendarPermission,
                        onGoogle = {
                            if (hasCalendarPermission) showGoogle = true
                            else permissionLauncher.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR))
                        },
                        onAccountSettings = {
                            runCatching { context.startActivity(Intent(Settings.ACTION_SYNC_SETTINGS)) }.onFailure { message("Android 설정에서 계정 동기화를 확인해 주세요.") }
                        }, onNaver = { showNaver = true },
                        onDisconnect = { scope.launch { repository.disconnectNaver(); CalendarSyncJob.schedule(context); revision++ } },
                        onVisibility = { id -> repository.setVisible(id, id in hidden); hidden = repository.hiddenCalendars(); scope.launch { WidgetUpdater.update(context) } },
                        onColor = { colorCalendar = it },
                        onRefresh = ::refreshManually, onGoogleSync = ::requestGoogleSync,
                        onEnableCalendar = { id -> scope.launch {
                            try { repository.enableGoogleCalendar(id); revision++; requestGoogleSync() }
                            catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; message(e.message ?: "캘린더 설정을 확인해 주세요.") }
                        } })
                }
            }
        }
    }
    if (showGoogle && !showGoogleConnect) GoogleAccountDialog(snapshot.googleAccounts, selectedGoogleAccount,
        onDismiss = { showGoogle = false },
        onSelect = ::selectGoogle,
        onAddAccount = { googleConnectError = ""; showGoogleConnect = true })
    if (showGoogleConnect) GoogleConnectDialog(
        onDismiss = { if (!addingGoogle) { showGoogleConnect = false; showGoogle = true } },
        onExistingAccount = {
            googleConnectError = ""
            val selectedAccount = selectedGoogleAccount?.let { Account(it, "com.google") }
            val chooser = AccountManager.newChooseAccountIntent(selectedAccount, null, arrayOf("com.google"), "모아에서 사용할 Google 계정", null, null, null)
            runCatching { accountLauncher.launch(chooser) }.onFailure {
                runCatching { context.startActivity(Intent(Settings.ACTION_ADD_ACCOUNT).putExtra(Settings.EXTRA_ACCOUNT_TYPES, arrayOf("com.google"))) }
                    .onFailure { googleConnectError = "Android 설정 → 계정에서 Google 계정을 추가해 주세요." }
            }
        },
        onNewAccount = {
            addingGoogle = true
            googleConnectError = ""
            try {
                AccountManager.get(context).addAccount("com.google", null, null, null, context as Activity, { future ->
                    addingGoogle = false
                    try {
                        val result = future.result
                        val name = result.getString(AccountManager.KEY_ACCOUNT_NAME)
                        if (result.getString(AccountManager.KEY_ACCOUNT_TYPE) == "com.google" && !name.isNullOrBlank()) selectGoogle(name)
                        else { showGoogleConnect = false; showGoogle = true; revision++ }
                    } catch (_: OperationCanceledException) { /* Cancellation keeps the selected account. */ }
                    catch (_: Exception) { googleConnectError = "Google 로그인 화면을 열지 못했어요. 기기의 Google 계정 설정을 확인해 주세요." }
                }, null)
            } catch (_: Exception) {
                addingGoogle = false
                googleConnectError = "Google 로그인 화면을 열지 못했어요. 기기의 Google 계정 설정을 확인해 주세요."
            }
        }, busy = addingGoogle, error = googleConnectError)
    colorCalendar?.let { calendar ->
        CalendarColorDialog(calendar, repository.defaultCalendarDisplayColor(calendar), repository.hasCalendarDisplayColor(calendar),
            onDismiss = { colorCalendar = null }) { color ->
            repository.setCalendarDisplayColor(calendar, color)
            snapshot = repository.applyDisplayColors(snapshot)
            colorCalendar = null
            scope.launch { WidgetUpdater.update(context) }
        }
    }
    if (showEditor) EventEditor(selected, snapshot.calendars.filter { it.supportsEvents && it.syncEnabled }, editing, onDismiss = { showEditor = false },
        onSave = { draft -> repository.save(draft, editing); showEditor = false; select(Instant.ofEpochMilli(draft.startMillis).atZone(if (draft.allDay) ZoneId.of("UTC") else ZoneId.systemDefault()).toLocalDate()); revision++; message(if (editing == null) "일정을 저장했어요." else "일정을 수정했어요.") },
        onDelete = { editing?.let { repository.delete(it) }; showEditor = false; revision++; message("일정을 삭제했어요.") })
    selectedTask?.let { task -> TaskDialog(task, snapshot.calendars.firstOrNull { it.id == task.calendarId }, onDismiss = { selectedTask = null }) }
    if (showNaver) NaverDialog(onDismiss = { showNaver = false }) { username, password ->
        repository.connectNaver(username, password)
        showNaver = false
        CalendarSyncJob.scheduleInitial(context, from, to)
        CalendarSyncJob.schedule(context)
        message("네이버 계정이 연결됐어요. 일정은 백그라운드에서 가져옵니다.")
    }
}

@Composable fun SyncRefreshAction(syncing: Boolean, onRefresh: () -> Unit) {
    if (syncing) Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(18.dp).semantics { contentDescription = "동기화 중" }, strokeWidth = 2.dp)
    } else IconButton(onClick = onRefresh, modifier = Modifier.size(44.dp)) { LineIcon("sync", Muted, label = "일정 새로고침") }
}

@Composable fun BrandMark() {
    Box(Modifier.size(27.dp)) {
        Box(Modifier.size(19.dp, 23.dp).align(Alignment.TopStart).clip(RoundedCornerShape(6.dp)).background(Color(0xFFB0B8F7)))
        Box(Modifier.size(19.dp, 23.dp).align(Alignment.BottomEnd).clip(RoundedCornerShape(6.dp)).background(Accent), contentAlignment = Alignment.Center) {
            LineIcon("check", Color.White, Modifier.size(13.dp))
        }
    }
}

@Composable private fun CalendarPage(
    month: YearMonth, selected: LocalDate, events: List<CalendarEvent>, snapshot: CalendarSnapshot, hidden: Set<String>, mode: Int,
    onMode: (Int) -> Unit, onDate: (LocalDate) -> Unit, onMonth: (Long) -> Unit, onToday: () -> Unit,
    onFilter: (String) -> Unit, onEvent: (CalendarEvent) -> Unit, onConnect: () -> Unit, onAdd: () -> Unit,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 96.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 18.dp, top = 8.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${month.year}  ${month.month.name}", color = Muted, fontSize = 10.sp, letterSpacing = 1.8.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("${month.monthValue}월", style = MaterialTheme.typography.headlineLarge)
                        Text("  나의 캘린더", Modifier.padding(bottom = 5.dp), fontSize = 14.sp, color = Muted)
                    }
                }
                TextButton(onClick = onToday, colors = ButtonDefaults.textButtonColors(contentColor = Ink), contentPadding = PaddingValues(horizontal = 14.dp)) {
                    Text("오늘", fontSize = 12.sp)
                }
                IconButton(onClick = { onMonth(-1) }, Modifier.size(38.dp)) { LineIcon("left", label = "이전 달") }
                IconButton(onClick = { onMonth(1) }, Modifier.size(38.dp)) { LineIcon("right", label = "다음 달") }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xFFEBEDF4)).padding(3.dp)) {
                    listOf("월", "주", "일정").forEachIndexed { index, text ->
                        Box(Modifier.clip(RoundedCornerShape(9.dp)).background(if (mode == index) Color.White else Color.Transparent)
                            .clickable { onMode(index) }.padding(horizontal = 18.dp, vertical = 8.dp)) {
                            Text(text, fontSize = 12.sp, color = if (mode == index) Ink else Muted, fontWeight = FontWeight.Medium)
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                Text("${snapshot.calendars.size}개 캘린더", fontSize = 11.sp, color = Muted)
            }
            Spacer(Modifier.height(10.dp))
            Surface(Modifier.padding(horizontal = 14.dp), color = Color.White, shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.padding(horizontal = 5.dp, vertical = 8.dp)) {
                    CalendarGrid(month, selected, events, mode == 1, showTitles = true, onDate = onDate, onEvent = onEvent)
                    HorizontalDivider(Modifier.padding(horizontal = 8.dp, vertical = 10.dp), color = LineColor)
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        snapshot.calendars.take(3).forEach { calendar ->
                            Row(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable { onFilter(calendar.id) }.padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(6.dp).clip(CircleShape).background(if (calendar.id in hidden) LineColor else Color(calendar.color)))
                                Spacer(Modifier.width(6.dp))
                                Text(calendar.name, fontSize = 10.sp, color = if (calendar.id in hidden) Color.LightGray else Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        if (snapshot.calendars.isEmpty()) Text("캘린더를 연결해 일정을 모아 보세요", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(8.dp))
                    }
                    TextButton(onClick = { scope.launch { listState.animateScrollToItem(1) } }, modifier = Modifier.fillMaxWidth()) {
                        Text("${selected.monthValue}월 ${selected.dayOfMonth}일 일정 ${events.count { it.occursOn(selected) }}개 보기 ↓", fontSize = 12.sp)
                    }
                }
            }
        }
        item {
            val dayEvents = events.filter { it.occursOn(selected) }
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(selected.format(DateTimeFormatter.ofPattern("M월 d일 EEEE", Locale.KOREAN)), fontSize = 17.sp, fontWeight = FontWeight.Bold)
                if (selected == LocalDate.now()) Text("TODAY", Modifier.padding(start = 8.dp).clip(RoundedCornerShape(5.dp)).background(AccentWash).padding(horizontal = 6.dp, vertical = 3.dp), fontSize = 8.sp, color = Accent, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text("${dayEvents.size}개의 일정", fontSize = 11.sp, color = Muted)
            }
            if (dayEvents.isEmpty()) EmptyDay(onAdd)
        }
        items(events.filter { it.occursOn(selected) }.sortedWith(compareBy<CalendarEvent> { !it.allDay }.thenBy { it.startMillis }), key = { it.id }) { event ->
            val calendar = snapshot.calendars.firstOrNull { it.id == event.calendarId }
            EventRow(event, calendar, onEvent)
        }
        item {
            if (snapshot.calendars.isEmpty()) Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 19.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onConnect).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                LineIcon("link", Muted, Modifier.size(15.dp))
                Text("  내 Google · 네이버 캘린더 연결하기", fontSize = 11.sp, color = Muted)
                Spacer(Modifier.weight(1f)); LineIcon("right", Muted, Modifier.size(15.dp))
            }
        }
    }
}

@Composable fun CalendarGrid(month: YearMonth, selected: LocalDate, events: List<CalendarEvent>, weekOnly: Boolean = false, showTitles: Boolean = false, onDate: (LocalDate) -> Unit = {}, onEvent: ((CalendarEvent) -> Unit)? = null) {
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val first = month.atDay(1)
    val gridStart = if (weekOnly) selected.minusDays((selected.dayOfWeek.value % 7).toLong()) else first.minusDays((first.dayOfWeek.value % 7).toLong())
    val weeks = if (weekOnly) 1 else (first.dayOfWeek.value % 7 + month.lengthOfMonth() + 6) / 7
    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        listOf("일", "월", "화", "수", "목", "금", "토").forEachIndexed { i, title ->
            Text(title, Modifier.weight(1f).padding(vertical = 5.dp), textAlign = TextAlign.Center, fontSize = 10.sp,
                color = when (i) { 0 -> Color(0xFFD89292); 6 -> Color(0xFF8A9ED0); else -> Muted })
        }
    }
    val zone = ZoneId.systemDefault()
    val layouts = remember(events, gridStart, weeks, zone) {
        (0 until weeks).map { calendarWeekLayout(events, gridStart.plusWeeks(it.toLong()), zone = zone) }
    }
    layouts.forEachIndexed { index, layout ->
        val weekStart = gridStart.plusWeeks(index.toLong())
        val rowHeight = if (showTitles) maxOf(48f, 32 + 16 * fontScale * layout.rowCount).dp else 48.dp
        Box(Modifier.fillMaxWidth().height(rowHeight)) {
            // Date targets sit behind app event actions; preview grids still select any tapped date.
            Row(Modifier.matchParentSize()) {
                repeat(7) { column ->
                    val date = weekStart.plusDays(column.toLong())
                    val dayEvents = layout.segments.filter { column in it.startColumn..it.endColumn }
                    Box(Modifier.weight(1f).fillMaxHeight().clickable { onDate(date) }
                        .semantics { contentDescription = "${date.monthValue}월 ${date.dayOfMonth}일, 일정 ${layout.eventCounts[column]}개" +
                            dayEvents.joinToString(prefix = if (dayEvents.isEmpty()) "" else ": ") { it.event.title } })
                }
            }
            Column {
                Row(Modifier.fillMaxWidth()) {
                    repeat(7) { column ->
                        val date = weekStart.plusDays(column.toLong())
                        val isSelected = date == selected
                        val isToday = date == LocalDate.now()
                        Box(Modifier.weight(1f), contentAlignment = Alignment.TopCenter) {
                            Box(Modifier.size(28.dp).clip(RoundedCornerShape(11.dp))
                                .background(if (isSelected) Accent else if (isToday) AccentWash else Color.Transparent), contentAlignment = Alignment.Center) {
                                Text(date.dayOfMonth.toString(), fontSize = 13.sp, fontWeight = if (isSelected || isToday) FontWeight.Bold else FontWeight.Normal,
                                    color = when { isSelected -> Color.White; YearMonth.from(date) != month -> Color(0xFFCDD1DC); isToday -> Accent; column == 0 -> Color(0xFFD88585); column == 6 -> Color(0xFF7892C4); else -> Ink })
                            }
                        }
                    }
                }
                Spacer(Modifier.height(3.dp))
                if (showTitles) {
                    repeat(layout.rowCount) { lane ->
                        Row(Modifier.fillMaxWidth().height((16 * fontScale).dp)) {
                            var nextColumn = 0
                            layout.segments.filter { it.row == lane }.forEach { segment ->
                                if (segment.startColumn > nextColumn) Spacer(Modifier.weight((segment.startColumn - nextColumn).toFloat()))
                                val shape = RoundedCornerShape(
                                    topStart = if (segment.continuesBefore) 0.dp else 3.dp,
                                    bottomStart = if (segment.continuesBefore) 0.dp else 3.dp,
                                    topEnd = if (segment.continuesAfter) 0.dp else 3.dp,
                                    bottomEnd = if (segment.continuesAfter) 0.dp else 3.dp)
                                Text(segment.label(), Modifier.weight((segment.endColumn - segment.startColumn + 1).toFloat())
                                    .padding(horizontal = 1.dp).clip(shape).background(Color(segment.event.color).copy(alpha = .20f))
                                    .then(if (onEvent != null) Modifier.clickable { onEvent(segment.event) }
                                        .semantics { contentDescription = "${if (segment.event.task) "할 일" else "일정"} 열기: ${segment.event.title}" } else Modifier)
                                    .padding(horizontal = 2.dp),
                                    fontSize = 10.sp, lineHeight = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                nextColumn = segment.endColumn + 1
                            }
                            if (nextColumn < 7) Spacer(Modifier.weight((7 - nextColumn).toFloat()))
                        }
                    }
                } else Row(Modifier.fillMaxWidth()) {
                    repeat(7) { column ->
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            layout.segments.filter { column in it.startColumn..it.endColumn }.take(2).forEach { segment ->
                                Box(Modifier.width(24.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color(segment.event.color).copy(alpha = .72f)))
                                Spacer(Modifier.height(3.dp))
                            }
                        }
                    }
                }
            }

        }
    }
}

@Composable fun EventRow(event: CalendarEvent, calendar: CalendarInfo? = null, onEvent: (CalendarEvent) -> Unit) {
    val calendarName = calendar?.name.orEmpty()
    val accountLabel = calendar?.accountLabel().orEmpty()
    val action = if (event.editRestriction(calendar) == null) "수정" else "보기"
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 5.dp).clip(RoundedCornerShape(17.dp)).background(Color.White)
        .clickable { onEvent(event) }.padding(horizontal = 14.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.width(51.dp)) {
            Text(if (event.allDay) "종일" else eventTime(event.startMillis), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            if (!event.allDay) Text(eventTime(event.endMillis), color = Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
        }
        Box(Modifier.width(3.dp).height(34.dp).clip(CircleShape).background(Color(event.color)))
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(event.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (event.location.isNotBlank()) LineIcon("pin", Muted, Modifier.size(11.dp))
                Text(if (event.location.isNotBlank()) " ${event.location}" else if (event.recurring) "반복 일정" else calendarName,
                    fontSize = 10.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (accountLabel.isNotBlank()) Text(accountLabel, fontSize = 10.sp, color = Muted, modifier = Modifier.padding(top = 3.dp))
        }
        TextButton(onClick = { onEvent(event) }, modifier = Modifier.padding(start = 4.dp)
            .semantics { contentDescription = "일정 $action: ${event.title}" }, contentPadding = PaddingValues(horizontal = 10.dp)) {
            Text(action, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable private fun EmptyDay(onAdd: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).clip(RoundedCornerShape(20.dp)).background(Color.White).padding(25.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        LineIcon("moon", Accent, Modifier.size(30.dp)); Spacer(Modifier.height(10.dp))
        Text("조금은 여유로운 하루", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text("새로운 계획을 더해 볼까요?", fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 5.dp))
        TextButton(onClick = onAdd) { Text("일정 추가하기", fontSize = 12.sp) }
    }
}

@Composable private fun AgendaPage(events: List<CalendarEvent>, calendars: List<CalendarInfo>, selected: LocalDate, query: String, onDate: (LocalDate) -> Unit, onEvent: (CalendarEvent) -> Unit) {
    val filtered = events.filter { (query.isNotBlank() || it.isUpcoming(selected.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli())) &&
        (query.isBlank() || listOf(it.title, it.location, it.description).any { value -> value.contains(query, true) }) }
    LazyColumn(contentPadding = PaddingValues(bottom = 100.dp)) {
        item {
            Column(Modifier.padding(24.dp)) {
                Text(if (query.isBlank()) "다가오는 일정" else "검색 결과", style = MaterialTheme.typography.headlineLarge)
                Text(if (query.isBlank()) "${selected.monthValue}월 ${selected.dayOfMonth}일부터, 차근차근." else "불러온 기간에서 ${filtered.size}개의 일정을 찾았어요.", color = Muted, modifier = Modifier.padding(top = 8.dp))
                if (selected != LocalDate.now()) TextButton(onClick = { onDate(LocalDate.now()) }) { Text("오늘부터 보기") }
            }
        }
        filtered.sortedBy { it.startMillis }.groupBy { it.date() }.forEach { (date, dayEvents) ->
            item { Text(date.format(DateTimeFormatter.ofPattern("M월 d일 EEEE", Locale.KOREAN)), Modifier.padding(start = 24.dp, top = 19.dp, bottom = 9.dp), fontSize = 13.sp, fontWeight = FontWeight.Bold) }
            items(dayEvents, key = { it.id }) { EventRow(it, calendars.firstOrNull { calendar -> calendar.id == it.calendarId }, onEvent) }
        }
        if (filtered.isEmpty()) item { Text("표시할 일정이 없어요.", Modifier.fillMaxWidth().padding(40.dp), textAlign = TextAlign.Center, color = Muted) }
    }
}

fun eventTime(value: Long): String = Instant.ofEpochMilli(value).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))
