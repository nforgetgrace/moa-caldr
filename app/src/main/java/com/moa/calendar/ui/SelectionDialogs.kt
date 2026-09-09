package com.moa.calendar.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.moa.calendar.data.*

@Composable private fun PickerDialog(title: String, subtitle: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val maxHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() * .85f }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(16.dp).heightIn(max = maxHeight),
            shape = RoundedCornerShape(24.dp), color = CanvasColor) {
            Column(Modifier.padding(horizontal = 18.dp)) {
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, Modifier.weight(1f), fontSize = 21.sp, fontWeight = FontWeight.Bold)
                    IconButton(onClick = onDismiss) { LineIcon("close", label = "선택 창 닫기") }
                }
                Text(subtitle, fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 2.dp, bottom = 16.dp))
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(bottom = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
            }
        }
    }
}

@Composable private fun SourceBadge(source: CalendarSource, color: Color) {
    Box(Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(color.copy(alpha = .12f)), contentAlignment = Alignment.Center) {
        if (source == CalendarSource.DEVICE) LineIcon("calendar", color, Modifier.size(18.dp))
        else Text(if (source == CalendarSource.GOOGLE) "G" else "N", color = color, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable private fun PickerChoice(title: String, subtitle: String, source: CalendarSource, color: Color, selected: Boolean, onClick: () -> Unit) {
    Surface(shape = RoundedCornerShape(15.dp), color = if (selected) color.copy(alpha = .08f) else Color.White,
        border = BorderStroke(1.dp, if (selected) color.copy(alpha = .65f) else LineColor)) {
        Row(Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .heightIn(min = 64.dp).padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            SourceBadge(source, color)
            Column(Modifier.weight(1f).padding(start = 10.dp, end = 6.dp)) {
                Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                if (subtitle.isNotBlank()) Text(subtitle, Modifier.padding(top = 4.dp), fontSize = 11.sp, color = Muted,
                    maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
            }
            Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                if (selected) LineIcon("check", color, Modifier.size(18.dp))
            }
        }
    }
}

@Composable fun GoogleAccountDialog(accounts: List<String>, selected: String?, onDismiss: () -> Unit,
    onSelect: (String?) -> Unit, onAddAccount: () -> Unit) {
    val choices = (accounts + listOfNotNull(selected)).distinctBy { it.trim().lowercase() }
    val green = Color(CalendarSource.GOOGLE.displayColor(0))
    PickerDialog("Google 계정 선택", "앱과 위젯에 표시할 계정을 골라 주세요.", onDismiss) {
        choices.forEach { account ->
            val synced = accounts.any { it.equals(account, ignoreCase = true) }
            PickerChoice(account, if (synced) "" else "기기 캘린더 동기화 대기", CalendarSource.GOOGLE, green,
                selected?.equals(account, ignoreCase = true) == true, { onSelect(account) })
        }
        if (accounts.distinctBy { it.lowercase() }.size > 1) {
            PickerChoice("모든 Google 계정", "기기에 동기화된 계정을 함께 표시", CalendarSource.GOOGLE, green, selected == null, { onSelect(null) })
        }
        if (accounts.isEmpty()) Text("동기화된 Google 캘린더가 아직 없어요. 계정을 추가하거나 기기 동기화를 확인해 주세요.", fontSize = 12.sp, color = Muted)
        FilledTonalButton(onClick = onAddAccount, modifier = Modifier.fillMaxWidth().padding(top = 6.dp), shape = RoundedCornerShape(13.dp)) {
            LineIcon("plus", Accent, Modifier.size(16.dp))
            Text("다른 계정 선택 · 추가", Modifier.padding(start = 8.dp), fontSize = 13.sp)
        }
    }
}

@Composable fun GoogleConnectDialog(onDismiss: () -> Unit, onExistingAccount: () -> Unit, onNewAccount: () -> Unit, busy: Boolean, error: String) {
    val green = Color(CalendarSource.GOOGLE.displayColor(0))
    PickerDialog("Google 계정 연결", "어떤 계정을 연결할까요?", onDismiss) {
        @Composable fun action(title: String, subtitle: String, icon: String, onClick: () -> Unit) {
            Surface(shape = RoundedCornerShape(16.dp), color = Color.White, border = BorderStroke(1.dp, LineColor)) {
                Row(Modifier.fillMaxWidth().clickable(enabled = !busy, role = Role.Button, onClick = onClick)
                    .padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(green.copy(alpha = .1f)), contentAlignment = Alignment.Center) {
                        LineIcon(icon, green, Modifier.size(21.dp))
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(subtitle, Modifier.padding(top = 5.dp), color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
                    }
                    LineIcon("right", Muted, Modifier.size(16.dp))
                }
            }
        }
        action("기기에 있는 계정", "휴대폰의 계정을 선택해요.", "calendar", onExistingAccount)
        action("새 Google 계정 추가", "새 계정으로 로그인해요.", "plus", onNewAccount)
        Text(if (busy) "Google 로그인 화면을 여는 중…" else "다음 단계는 Android · Google 화면에서 진행됩니다.",
            Modifier.padding(horizontal = 2.dp, vertical = 6.dp), fontSize = 11.sp, color = Muted, lineHeight = 17.sp)
        if (error.isNotBlank()) Text(error, fontSize = 12.sp, color = MaterialTheme.colorScheme.error, lineHeight = 18.sp)
    }
}

@Composable fun CalendarDestination(calendar: CalendarInfo?, enabled: Boolean, onClick: () -> Unit) {
    Surface(shape = RoundedCornerShape(14.dp), color = Color.White, border = BorderStroke(1.dp, LineColor)) {
        Row(Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = if (enabled) "저장 캘린더 선택" else "원본 캘린더" }
            .padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (calendar != null) SourceBadge(calendar.source, Color(calendar.color))
            Column(Modifier.weight(1f).padding(start = if (calendar == null) 0.dp else 12.dp, end = 8.dp)) {
                Text(calendar?.name ?: "캘린더 선택", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (calendar != null) Text(calendar.selectionSubtitle(), Modifier.padding(top = 4.dp), fontSize = 11.sp, color = Muted,
                    maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
            }
            if (enabled) LineIcon("right", Muted, Modifier.size(18.dp))
        }
    }
}

@Composable fun CalendarSelectionDialog(calendars: List<CalendarInfo>, selectedId: String, onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    PickerDialog("저장할 캘린더", "일정을 등록할 원본 캘린더를 골라 주세요.", onDismiss) {
        CalendarSource.entries.forEach { source ->
            val choices = calendars.filter { it.source == source && it.writable && it.syncEnabled && it.supportsEvents }
            if (choices.isNotEmpty()) {
                Text(choices.first().copy(account = "").accountLabel(), Modifier.padding(top = 6.dp, bottom = 2.dp),
                    fontSize = 12.sp, color = Muted, fontWeight = FontWeight.SemiBold)
                choices.forEach { calendar ->
                    PickerChoice(calendar.name, calendar.selectionSubtitle(), source, Color(calendar.color), calendar.id == selectedId, { onSelect(calendar.id) })
                }
            }
        }
    }
}
