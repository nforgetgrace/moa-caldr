package com.moa.calendar.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moa.calendar.data.*
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@Composable fun TasksPage(tasks: List<CalendarTask>, calendars: List<CalendarInfo>, notice: String, query: String, onTask: (CalendarTask) -> Unit) {
    var showCompleted by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val filtered = tasks.filter { (showCompleted || !it.completed) &&
        (query.isBlank() || it.title.contains(query, true) || it.description.contains(query, true)) }
        .sortedWith(compareBy<CalendarTask> { it.completed }.thenBy { it.dueMillis ?: Long.MAX_VALUE })
    LazyColumn(contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("내 할 일", style = MaterialTheme.typography.headlineLarge)
            Text(notice, fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 10.dp))
            Row(Modifier.fillMaxWidth().clickable { showCompleted = !showCompleted }, verticalAlignment = Alignment.CenterVertically) {
                Text("완료한 할 일도 보기", fontSize = 12.sp, modifier = Modifier.weight(1f))
                Switch(showCompleted, { showCompleted = it })
            }
        }
        items(filtered, key = { it.id }) { task ->
            Surface(shape = RoundedCornerShape(16.dp), color = Color.White) {
                Row(Modifier.fillMaxWidth().clickable { onTask(task) }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (task.completed) "✓" else "□", color = Color(task.color), fontSize = 22.sp)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(task.title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text(taskDeadline(task), fontSize = 11.sp, color = Muted, modifier = Modifier.padding(top = 5.dp))
                        calendars.firstOrNull { it.id == task.calendarId }?.let {
                            Text(it.accountLabel(), fontSize = 10.sp, color = Muted, modifier = Modifier.padding(top = 3.dp))
                        }
                    }
                }
            }
        }
        if (filtered.isEmpty() && tasks.isNotEmpty()) item { Text("조건에 맞는 할 일이 없어요.", fontSize = 13.sp, color = Muted) }
        item {
            TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://calendar.naver.com/"))) } }) {
                Text("네이버에서 내 할 일 확인 ↗", fontSize = 12.sp)
            }
            Text("날짜가 없는 할 일도 이 목록에서 확인할 수 있어요. 마감일이 있는 미완료 항목은 달력과 위젯에 □로 표시합니다.", fontSize = 11.sp, color = Muted)
        }
    }
}

@Composable fun TaskDialog(task: CalendarTask, calendar: CalendarInfo?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(onDismissRequest = onDismiss, title = { Text(task.title) }, text = {
        Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(calendar?.accountLabel().orEmpty(), fontSize = 12.sp, color = Muted)
            Text(calendar?.name.orEmpty(), fontSize = 11.sp, color = Muted)
            Text(if (task.completed) "완료한 할 일" else "미완료", fontSize = 13.sp)
            Text(taskDeadline(task), fontSize = 13.sp)
            if (task.description.isNotBlank()) Text(task.description, fontSize = 13.sp)
            if (task.recurring) Text("반복 할 일 · 다음 회차와 완료 처리는 원본에서 확인해 주세요.", fontSize = 11.sp, color = Muted)
            Text("완료·수정·삭제는 네이버에서 관리합니다.", fontSize = 11.sp, color = Muted)
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
        dismissButton = { TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://calendar.naver.com/"))) } }) { Text("네이버 열기 ↗") } })
}

private fun taskDeadline(task: CalendarTask): String = task.dueMillis?.let {
    Instant.ofEpochMilli(it).atZone(if (task.dueAllDay) ZoneOffset.UTC else ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern(if (task.dueAllDay) "yyyy. M. d 마감" else "yyyy. M. d HH:mm 마감"))
} ?: "마감일 없음"
