package com.moa.calendar.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.moa.calendar.data.*
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun EventEditor(
    selected: LocalDate, calendars: List<CalendarInfo>, existing: CalendarEvent?, onDismiss: () -> Unit,
    onSave: suspend (EventDraft) -> Unit, onDelete: suspend () -> Unit,
) {
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val zone = if (existing?.allDay == true) ZoneOffset.UTC else ZoneId.systemDefault()
    val start = existing?.let { Instant.ofEpochMilli(it.startMillis).atZone(zone) }
    val end = existing?.let { Instant.ofEpochMilli(it.endMillis).atZone(zone) }
    var title by remember { mutableStateOf(existing?.title.orEmpty()) }
    var location by remember { mutableStateOf(existing?.location.orEmpty()) }
    var description by remember { mutableStateOf(existing?.description.orEmpty()) }
    var allDay by remember { mutableStateOf(existing?.allDay ?: false) }
    var startDate by remember { mutableStateOf(start?.toLocalDate() ?: selected) }
    var endDate by remember { mutableStateOf(if (existing?.allDay == true) end!!.toLocalDate().minusDays(1) else end?.toLocalDate() ?: selected) }
    var startTime by remember { mutableStateOf(start?.toLocalTime() ?: LocalTime.of(10, 0)) }
    var endTime by remember { mutableStateOf(end?.toLocalTime() ?: LocalTime.of(11, 0)) }
    var calendarId by remember { mutableStateOf(existing?.calendarId ?: calendars.firstOrNull { it.writable }?.id.orEmpty()) }
    var menu by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    val readOnly = existing != null && (existing.task || existing.recurring || calendars.none { it.id == existing.calendarId && it.writable } ||
        existing.rawIcs.contains("ATTENDEE", true) || existing.rawIcs.contains("ORGANIZER", true))

    fun chooseDate(isStart: Boolean) {
        val value = if (isStart) startDate else endDate
        DatePickerDialog(context, { _, year, month, day ->
            val picked = LocalDate.of(year, month + 1, day)
            if (isStart) { startDate = picked; if (endDate < picked) endDate = picked } else endDate = picked
        }, value.year, value.monthValue - 1, value.dayOfMonth).show()
    }
    fun chooseTime(isStart: Boolean) {
        val time = if (isStart) startTime else endTime
        TimePickerDialog(context, { _, hour, minute ->
            if (isStart) startTime = LocalTime.of(hour, minute) else endTime = LocalTime.of(hour, minute)
        }, time.hour, time.minute, true).show()
    }
    ModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = CanvasColor) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).imePadding().padding(bottom = 24.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (existing == null) "새로운 일정" else "일정 상세", fontSize = 23.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onDismiss, enabled = !busy) { LineIcon("close", label = "닫기") }
            }
            val sourceCalendar = calendars.firstOrNull { it.id == calendarId }
            if (existing == null) Text("선택한 원본 캘린더에 저장해요", color = Muted, fontSize = 11.sp)
            Spacer(Modifier.height(20.dp))
            OutlinedTextField(title, { title = it }, label = { Text("일정 제목") }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !busy && !readOnly, shape = RoundedCornerShape(14.dp))
            Spacer(Modifier.height(12.dp))
            CalendarDestination(sourceCalendar, enabled = existing == null && !busy) {
                focus.clearFocus()
                keyboard?.hide()
                menu = true
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("하루 종일", Modifier.weight(1f)); Switch(allDay, { allDay = it }, enabled = !busy && !readOnly, modifier = Modifier.semantics { contentDescription = "하루 종일" })
            }
            listOf(true, false).forEach { isStart ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (isStart) "시작" else "종료", Modifier.width(40.dp), fontSize = 13.sp, color = Muted)
                    TextButton(onClick = { chooseDate(isStart) }, enabled = !busy && !readOnly) { Text((if (isStart) startDate else endDate).format(DateTimeFormatter.ofPattern("yyyy. M. d"))) }
                    Spacer(Modifier.weight(1f))
                    if (!allDay) TextButton(onClick = { chooseTime(isStart) }, enabled = !busy && !readOnly) { Text((if (isStart) startTime else endTime).format(DateTimeFormatter.ofPattern("HH:mm"))) }
                }
            }
            OutlinedTextField(location, { location = it }, label = { Text("장소") }, leadingIcon = { LineIcon("pin", Muted) }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp), singleLine = true, enabled = !busy && !readOnly, shape = RoundedCornerShape(14.dp))
            OutlinedTextField(description, { description = it }, label = { Text("메모") }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp), minLines = 2, maxLines = 4, enabled = !busy && !readOnly, shape = RoundedCornerShape(14.dp))
            if (readOnly) Text("반복·초대 일정 또는 읽기 전용 캘린더예요. 변경은 원본 캘린더에서 해 주세요.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 14.dp))
            if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
            if (!readOnly) {
                Button(onClick = {
                    scope.launch {
                        busy = true; error = ""
                        try {
                            val from = if (allDay) startDate.atStartOfDay(ZoneOffset.UTC) else startDate.atTime(startTime).atZone(ZoneId.systemDefault())
                            val to = if (allDay) endDate.plusDays(1).atStartOfDay(ZoneOffset.UTC) else endDate.atTime(endTime).atZone(ZoneId.systemDefault())
                            val draft = EventDraft(calendarId, title, from.toInstant().toEpochMilli(), to.toInstant().toEpochMilli(), allDay, description, location)
                            validateDraft(draft); onSave(draft)
                        } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; error = e.message ?: "저장하지 못했어요." }
                        finally { busy = false }
                    }
                }, Modifier.fillMaxWidth().padding(top = 20.dp).height(52.dp), enabled = !busy && calendarId.isNotBlank(), shape = RoundedCornerShape(16.dp)) {
                    if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                    else Text("일정 저장", fontWeight = FontWeight.Bold)
                }
                if (existing != null) TextButton(onClick = { confirmDelete = true }, Modifier.fillMaxWidth(), enabled = !busy) { Text("일정 삭제", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
    if (menu) CalendarSelectionDialog(calendars, calendarId, onDismiss = { menu = false }) { id -> calendarId = id; menu = false }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("일정을 삭제할까요?") },
        text = { Text("‘${existing?.title}’ 일정이 원본 캘린더에서도 삭제됩니다.") },
        confirmButton = { TextButton(onClick = {
            confirmDelete = false
            scope.launch { busy = true; try { onDelete() } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; error = e.message ?: "삭제하지 못했어요." } finally { busy = false } }
        }) { Text("삭제", color = MaterialTheme.colorScheme.error) } }, dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("취소") } })
}

@Composable fun NaverDialog(onDismiss: () -> Unit, onConnect: suspend (String, String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().padding(20.dp).heightIn(max = 650.dp), shape = RoundedCornerShape(26.dp), color = CanvasColor) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp).imePadding()) {
                Text("네이버 캘린더 연결", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("CalDAV · 실험적 연결", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 7.dp))
                Text("서버  caldav.calendar.naver.com\n보안 연결  SSL · 443\n계정 주소는 로그인 후 자동으로 찾습니다.", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 12.dp))
                Spacer(Modifier.height(19.dp))
                Text("네이버는 Android CalDAV를 공식 지원하지 않아 계정에 따라 연결이 제한될 수 있어요. 2단계 인증을 사용한다면 앱 비밀번호를 입력해 주세요.", fontSize = 13.sp, color = Muted)
                OutlinedTextField(username, { username = it }, Modifier.fillMaxWidth().padding(top = 18.dp), label = { Text("네이버 아이디") }, singleLine = true, enabled = !busy, shape = RoundedCornerShape(13.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth().padding(top = 10.dp), label = { Text("비밀번호 또는 앱 비밀번호") }, singleLine = true, enabled = !busy, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), shape = RoundedCornerShape(13.dp))
                TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://help.naver.com/service/5640/contents/8584"))) } }, enabled = !busy) { Text("앱 비밀번호 발급 안내 ↗", fontSize = 12.sp) }
                Text("비밀번호는 기기 안에 암호화해 보관하고 네이버 서버로만 전송해요.", fontSize = 11.sp, color = Muted)
                if (error.isNotBlank()) Text(error, Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                Button(onClick = {
                    scope.launch { busy = true; error = ""; try { onConnect(username, password); password = "" } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; error = e.message ?: "연결에 실패했어요." } finally { busy = false } }
                }, Modifier.fillMaxWidth().padding(top = 20.dp).height(50.dp), enabled = !busy && username.isNotBlank() && password.isNotBlank(), shape = RoundedCornerShape(14.dp)) {
                    if (busy) { CircularProgressIndicator(Modifier.size(18.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp); Text("  로그인 확인 중…") }
                    else Text("연결하기")
                }
                TextButton(onClick = onDismiss, Modifier.fillMaxWidth(), enabled = !busy) { Text("닫기") }
            }
        }
    }
}
