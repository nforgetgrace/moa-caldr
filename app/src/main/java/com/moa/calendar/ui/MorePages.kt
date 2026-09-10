package com.moa.calendar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moa.calendar.data.*
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable fun SettingsPage(
    snapshot: CalendarSnapshot, naverConnected: Boolean, naverAccount: String, hidden: Set<String>,
    googleAccount: String?, hasCalendarPermission: Boolean,
    onGoogle: () -> Unit, onAccountSettings: () -> Unit, onNaver: () -> Unit, onDisconnect: () -> Unit,
    onVisibility: (String) -> Unit, onColor: (CalendarInfo) -> Unit, onRefresh: () -> Unit, onGoogleSync: () -> Unit, onEnableCalendar: (String) -> Unit,
) {
    var disconnectDialog by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(top = 20.dp, bottom = 30.dp)) {
        Text("나의 캘린더 공간", style = MaterialTheme.typography.headlineLarge)
        Text("흩어진 일정, 이제 한곳에서.", color = Muted, modifier = Modifier.padding(top = 9.dp, bottom = 27.dp))
        Text("연결된 계정", fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 12.dp))
        AccountCard("G", Color(0xFF4285F4), "Google Calendar",
            googleAccount ?: if (snapshot.googleAccounts.isNotEmpty()) "기기의 Google 계정 ${snapshot.googleAccounts.size}개" else "사용할 Google 계정을 선택하세요",
            if (hasCalendarPermission) "Google 계정 선택 · 변경" else "캘린더 접근 허용 · 계정 선택", onGoogle)
        if (hasCalendarPermission) {
            Text(snapshot.googleSyncNotice, fontSize = 11.sp, color = Muted, modifier = Modifier.padding(top = 10.dp))
            TextButton(onClick = onGoogleSync, enabled = googleAccount != null) { Text("선택한 Google 계정에서 다시 가져오기", fontSize = 12.sp) }
        }
        TextButton(onClick = onAccountSettings, contentPadding = PaddingValues(horizontal = 4.dp)) { Text("Google 계정 · 기기 동기화 설정 열기 ↗", fontSize = 11.sp) }
        Spacer(Modifier.height(7.dp))
        AccountCard("N", Color(0xFF03A86B), "NAVER Calendar", if (naverConnected) "$naverAccount · 연결됨" else "CalDAV로 개인 일정을 연결하세요",
            if (naverConnected) "연결 다시 설정" else "계정 연결", onNaver)
        if (snapshot.initialNaverSync) Text("로그인 완료 · 일정은 백그라운드에서 가져옵니다.", fontSize = 11.sp, color = Muted, modifier = Modifier.padding(top = 9.dp))
        Text("네이버의 Android CalDAV는 공식 지원 대상이 아니며, 실제 연결 확인이 필요해요.", fontSize = 11.sp, color = Muted, modifier = Modifier.padding(top = 9.dp, bottom = 8.dp))
        if (naverConnected) TextButton(onClick = { disconnectDialog = true }) { Text("네이버 연결 해제", fontSize = 11.sp, color = MaterialTheme.colorScheme.error) }
        Spacer(Modifier.height(22.dp))
        Text("표시할 캘린더", fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 12.dp))
        val groups = listOf(
            Triple("Google · ${googleAccount ?: "기기의 모든 Google 계정"}", CalendarSource.GOOGLE, "선택한 Google 계정의 캘린더가 아직 기기에 없어요. 위의 다시 가져오기 또는 기기 동기화 설정을 이용해 주세요."),
            Triple("NAVER · ${if (naverConnected) naverAccount else "연결 필요"}", CalendarSource.NAVER, "네이버 계정을 연결해 주세요."),
            Triple("이 기기의 다른 캘린더", CalendarSource.DEVICE, ""),
        )
        groups.forEach { (heading, source, emptyMessage) ->
            val calendars = snapshot.calendars.filter { it.source == source }
            if (calendars.isNotEmpty() || source != CalendarSource.DEVICE) {
                Text(heading, fontSize = 11.sp, color = Muted, modifier = Modifier.padding(top = 14.dp, bottom = 8.dp))
                Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(19.dp), color = Color.White) {
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        if (calendars.isEmpty()) Text(emptyMessage, Modifier.padding(vertical = 18.dp), color = Muted, fontSize = 12.sp)
                        calendars.forEach { calendar ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(28.dp).clip(CircleShape).clickable { onColor(calendar) }
                                    .semantics { contentDescription = "${calendar.name} 색상 변경" }, contentAlignment = Alignment.Center) {
                                    Box(Modifier.size(12.dp).clip(CircleShape).background(Color(calendar.color)))
                                }
                                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                    Text(calendar.name, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                    Text(calendar.accountLabel() + if (!calendar.writable) " · 읽기 전용" else "", fontSize = 10.sp, color = Muted)
                                    if (!calendar.syncEnabled) Text("이 캘린더의 기기 동기화가 꺼져 있어요", fontSize = 10.sp, color = MaterialTheme.colorScheme.error)
                                    if (calendar.supportsTasks && !calendar.supportsEvents) Text("할 일 목록", fontSize = 10.sp, color = Muted)
                                }
                                TextButton(onClick = { onColor(calendar) }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                                    Text("색상", fontSize = 11.sp)
                                }
                                if (!calendar.syncEnabled && source == CalendarSource.GOOGLE) {
                                    TextButton(onClick = { onEnableCalendar(calendar.id) }) { Text("동기화 켜기", fontSize = 11.sp) }
                                } else Switch(calendar.id !in hidden, { onVisibility(calendar.id) }, modifier = Modifier.semantics { contentDescription = "${calendar.name} 표시" })
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(22.dp))
        Surface(shape = RoundedCornerShape(19.dp), color = Color.White) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                Column(Modifier.padding(vertical = 14.dp)) {
                    Text("자동 동기화", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Text("Google · 기기 동기화 후 자동 반영\nNAVER · 앱 사용 중 1분, 백그라운드 약 30분", fontSize = 11.sp, color = Muted, modifier = Modifier.padding(top = 7.dp))
                    Text("앱을 다시 열 때도 확인해요. 절전 상태에서는 갱신이 늦어질 수 있어요.", fontSize = 10.sp, color = Muted, modifier = Modifier.padding(top = 6.dp))
                }
                HorizontalDivider(color = LineColor)
                Row(Modifier.fillMaxWidth().clickable(onClick = onRefresh).padding(vertical = 17.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("일정 새로고침", fontSize = 13.sp)
                        Text(if (snapshot.lastSyncMillis > 0) "네이버 확인 · " + Instant.ofEpochMilli(snapshot.lastSyncMillis).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("M/d HH:mm")) else "Google은 기기 계정 동기화 설정을 따라요", fontSize = 10.sp, color = Muted)
                    }
                    LineIcon("sync", Accent)
                }
            }
        }
        Spacer(Modifier.height(27.dp))
        Text("일정은 선택한 원본 캘린더에 저장됩니다. Google과 네이버 간 자동 복제는 하지 않습니다. 네이버 비밀번호는 기기에 암호화해 보관합니다.", color = Muted, fontSize = 11.sp)
        Text("MOA  0.1.10  ·  Made for your everyday", color = Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 18.dp))
    }
    if (disconnectDialog) AlertDialog(onDismissRequest = { disconnectDialog = false }, title = { Text("네이버 연결을 해제할까요?") },
        text = { Text("이 기기의 로그인 정보와 저장된 네이버 일정만 지웁니다. 네이버에 있는 원본 일정은 유지됩니다.") },
        confirmButton = { TextButton(onClick = { disconnectDialog = false; onDisconnect() }) { Text("연결 해제") } },
        dismissButton = { TextButton(onClick = { disconnectDialog = false }) { Text("취소") } })
}

@Composable private fun AccountCard(letter: String, color: Color, title: String, subtitle: String, action: String, onAction: () -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = Color.White) {
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(41.dp).clip(RoundedCornerShape(13.dp)).background(color.copy(alpha = .08f)), contentAlignment = Alignment.Center) { Text(letter, fontSize = 23.sp, fontWeight = FontWeight.ExtraBold, color = color) }
                Column(Modifier.weight(1f).padding(start = 13.dp)) {
                    Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text(subtitle, fontSize = 11.sp, color = Muted, modifier = Modifier.padding(top = 5.dp), maxLines = if (subtitle.contains("@")) 1 else 2, overflow = TextOverflow.Ellipsis)
                }
            }
            FilledTonalButton(onClick = onAction, Modifier.fillMaxWidth().padding(top = 16.dp), shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.filledTonalButtonColors(containerColor = color.copy(alpha = .08f), contentColor = color)) { Text(action, fontSize = 12.sp) }
        }
    }
}

@Composable fun WidgetPage(events: List<CalendarEvent>, onPin: (Boolean) -> Unit) {
    val today = LocalDate.now()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(top = 20.dp, bottom = 35.dp)) {
        Text("열지 않아도, 한눈에", style = MaterialTheme.typography.headlineLarge)
        Text("나의 하루를 홈 화면에 꺼내 두세요.", color = Muted, modifier = Modifier.padding(top = 9.dp))
        Text("내 일정으로 보는 위젯 미리보기", color = Accent, fontSize = 11.sp, modifier = Modifier.padding(top = 10.dp, bottom = 24.dp))
        Surface(shape = RoundedCornerShape(25.dp), color = Color(0xFF303951)) {
            Column(Modifier.fillMaxWidth().padding(22.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("moa", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    Text("${today.monthValue}월 ${today.dayOfMonth}일", color = Color(0xFFBCC5DF), fontSize = 12.sp)
                }
                Spacer(Modifier.height(19.dp))
                val upcoming = events.filter { it.isUpcoming() }.take(3)
                if (upcoming.isEmpty()) Text("오늘은 여유로운 하루예요", color = Color.White, fontSize = 14.sp, modifier = Modifier.padding(vertical = 20.dp))
                upcoming.forEach { event ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(3.dp).height(32.dp).clip(CircleShape).background(Color(event.color)))
                        Column(Modifier.padding(start = 11.dp).weight(1f)) {
                            Text(event.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                            Text(if (event.allDay) "종일" else "${event.date().monthValue}/${event.date().dayOfMonth} · ${eventTime(event.startMillis)}", color = Color(0xFFBCC5DF), fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }
        }
        WidgetCaption("다가오는 일정", "4 × 2 · 다크", { onPin(true) })
        Spacer(Modifier.height(25.dp))
        Surface(shape = RoundedCornerShape(24.dp), color = Color.White) {
            Column(Modifier.fillMaxWidth().padding(15.dp)) {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${today.monthValue}월", fontSize = 23.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f)); Text("moa", color = Accent, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
                CalendarGrid(YearMonth.now(), today, events, showTitles = true)
            }
        }
        WidgetCaption("한 달 캘린더", "4 × 5 · 일정 제목 표시", { onPin(false) })
        Spacer(Modifier.height(25.dp))
        Text("홈 화면을 길게 누른 뒤 ‘위젯 → 모아 캘린더’에서도 추가할 수 있어요. 위젯을 누르면 앱으로 이동하며 크기도 조절할 수 있습니다.", fontSize = 12.sp, color = Muted)
        Text("자동 갱신은 약 30분 간격이며, 배터리·백그라운드 제한에 따라 늦어질 수 있어요.", fontSize = 11.sp, color = Muted, modifier = Modifier.padding(top = 10.dp))
    }
}

@Composable private fun WidgetCaption(title: String, subtitle: String, onPin: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold); Text(subtitle, color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp)) }
        TextButton(onClick = onPin) { LineIcon("plus", Accent, Modifier.size(15.dp)); Text(" 홈 화면에 추가", fontSize = 11.sp) }
    }
}
