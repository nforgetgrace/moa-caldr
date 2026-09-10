package com.moa.calendar.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.moa.calendar.data.CalendarColorChoice
import com.moa.calendar.data.CalendarColors
import com.moa.calendar.data.CalendarInfo

@Composable fun CalendarColorDialog(calendar: CalendarInfo, defaultColor: Int, customized: Boolean, onDismiss: () -> Unit, onSelect: (Int?) -> Unit) {
    val maxHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() * .85f }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.widthIn(max = 480.dp).fillMaxWidth().padding(16.dp).heightIn(max = maxHeight),
            shape = RoundedCornerShape(24.dp), color = CanvasColor) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("캘린더 색상", fontSize = 21.sp, fontWeight = FontWeight.Bold)
                        Text(calendar.name, Modifier.padding(top = 5.dp), fontSize = 12.sp, color = Muted,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = onDismiss) { LineIcon("close", label = "색상 선택 닫기") }
                }
                Column(Modifier.verticalScroll(rememberScrollState()).padding(top = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ColorChoiceRow("기본 색상", "서비스 기본 색상으로 되돌리기", Color(defaultColor), selected = !customized) {
                        onSelect(null)
                    }
                    CalendarColors.choices.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { choice ->
                                PaletteCell(choice, selected = customized && calendar.color == choice.color,
                                    modifier = Modifier.weight(1f), onClick = { onSelect(choice.color) })
                            }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun PaletteCell(choice: CalendarColorChoice, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val color = Color(choice.color)
    Surface(modifier, shape = RoundedCornerShape(16.dp), color = if (selected) color.copy(alpha = .10f) else Color.White,
        border = BorderStroke(1.dp, if (selected) color.copy(alpha = .65f) else LineColor)) {
        Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "${choice.name} 색상 선택"; this.selected = selected }
            .heightIn(min = 56.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(22.dp).clip(CircleShape).background(color))
            Text(choice.name, Modifier.weight(1f).padding(start = 10.dp), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            if (selected) LineIcon("check", color, Modifier.size(17.dp))
        }
    }
}

@Composable private fun ColorChoiceRow(title: String, subtitle: String, color: Color, selected: Boolean, onClick: () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = Color.White, border = BorderStroke(1.dp, LineColor)) {
        Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "$title 선택"; this.selected = selected }
            .heightIn(min = 58.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(22.dp).clip(CircleShape).background(color))
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(subtitle, Modifier.padding(top = 3.dp), fontSize = 10.sp, color = Muted)
            }
            if (selected) LineIcon("check", color, Modifier.size(17.dp))
        }
    }
}
