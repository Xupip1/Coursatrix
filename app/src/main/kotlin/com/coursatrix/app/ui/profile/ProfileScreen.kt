package com.coursatrix.app.ui.profile

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.coursatrix.app.ui.CoursatrixViewModel
import com.coursatrix.app.ui.components.*
import com.coursatrix.app.ui.theme.accentSwatch
import com.coursatrix.core.model.*

@Composable
fun ProfileScreen(data: AppData, model: CoursatrixViewModel, onSettings: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        PageHeader("我的学习空间", "不必登录，也可以好好安排每一天")
        Column(Modifier.padding(horizontal = 22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(data.semester.school.ifBlank { "欢迎使用 Coursatrix" }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("${data.semester.name} · ${data.semester.weekCount} 周 · 每天 ${data.periods.size} 节",
                        style = MaterialTheme.typography.bodyMedium)
                    Text("第一周周一：${data.semester.firstMonday}", style = MaterialTheme.typography.bodySmall)
                    FilledTonalButton(onSettings, Modifier.fillMaxWidth()) { Text("设置学期与作息") }
                }
            }
            AppearancePanel(data.preferences, model)
            ReminderPanel(data, model)
            TransferPanel(data, model)
            HelpSection()
        }
    }
}

@Composable
private fun AppearancePanel(prefs: Preferences, model: CoursatrixViewModel) {
    fun set(value: Preferences) = model.setPreferences(value)
    SectionTitle("外观与显示")
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Label("主题")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.entries.forEach { theme ->
                    FilterChip(prefs.theme == theme, { set(prefs.copy(theme = theme)) }, enabled = !model.busy, label = { Text(theme.label) })
                }
            }
            Label("主题色")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                AccentColor.entries.filter { it != AccentColor.DYNAMIC || Build.VERSION.SDK_INT >= 31 }.forEach { accent ->
                    Swatch(accent, prefs.accent == accent) { if (!model.busy) set(prefs.copy(accent = accent)) }
                }
            }
            Label("课程卡片")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CardStyle.entries.forEach { style ->
                    FilterChip(prefs.cardStyle == style, { set(prefs.copy(cardStyle = style)) }, enabled = !model.busy, label = { Text(style.label) })
                }
            }
            HorizontalDivider()
            Toggle("显示周末", prefs.showWeekend, model.busy) { set(prefs.copy(showWeekend = it)) }
            Toggle("显示非本周课程", prefs.showInactive, model.busy) { set(prefs.copy(showInactive = it)) }
            Toggle("课程卡片显示教室", prefs.showLocation, model.busy) { set(prefs.copy(showLocation = it)) }
            Toggle("课程卡片显示教师", prefs.showTeacher, model.busy) { set(prefs.copy(showTeacher = it)) }
            Toggle("显示每节上课时间", prefs.showPeriodTimes, model.busy) { set(prefs.copy(showPeriodTimes = it)) }
            Toggle("显示表格线", prefs.showGridLines, model.busy) { set(prefs.copy(showGridLines = it)) }
        }
    }
}

@Composable
private fun Label(text: String) =
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun Toggle(label: String, checked: Boolean, busy: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked, onChange, enabled = !busy)
    }
}

@Composable
private fun Swatch(accent: AccentColor, selected: Boolean, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val fill = if (accent == AccentColor.DYNAMIC)
            Brush.sweepGradient(AccentColor.entries.filter { it != AccentColor.DYNAMIC }.map(::accentSwatch) + accentSwatch(AccentColor.VIOLET))
            else Brush.linearGradient(listOf(accentSwatch(accent), accentSwatch(accent)))
        Box(Modifier.size(38.dp).clip(CircleShape).background(fill)
            .border(if (selected) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), CircleShape),
            contentAlignment = Alignment.Center) {
            if (selected) Icon(Icons.Outlined.Check, "已选择", tint = Color.White)
        }
        Text(accent.label, style = MaterialTheme.typography.labelSmall)
    }
}
