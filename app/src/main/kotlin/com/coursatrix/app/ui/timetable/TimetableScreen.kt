package com.coursatrix.app.ui.timetable

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.EditCalendar
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.coursatrix.app.ui.CoursatrixViewModel
import com.coursatrix.app.ui.components.ChoiceField
import com.coursatrix.app.ui.components.PageHeader
import com.coursatrix.app.ui.components.courseColor
import com.coursatrix.core.model.AppData
import com.coursatrix.core.model.CardStyle
import com.coursatrix.core.model.weekdayNames
import com.coursatrix.core.schedule.ScheduleCalculator
import com.coursatrix.core.schedule.Session
import com.coursatrix.core.validation.WeekPattern
import java.time.LocalDate

@Composable
fun TimetableScreen(data: AppData, today: LocalDate, model: CoursatrixViewModel, onCourse: (String) -> Unit, onSettings: () -> Unit) {
    val actualWeek = ScheduleCalculator.weekOn(data.semester, today)
    var week by rememberSaveable(data.semester.id) { mutableIntStateOf(actualWeek.coerceIn(1, data.semester.weekCount)) }
    var showWeeks by remember { mutableStateOf(false) }
    var settingWeek by remember { mutableStateOf(false) }
    var overlapping by remember { mutableStateOf<List<Session>>(emptyList()) }
    LaunchedEffect(data.semester.weekCount) { week = week.coerceIn(1, data.semester.weekCount) }
    val prefs = data.preferences
    val days = if (prefs.showWeekend) 7 else 5
    val monday = data.semester.startDate.plusWeeks((week - 1).toLong())
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val rowHeight = 92.dp * fontScale
    val columnWidth = 98.dp
    val timeWidth = 56.dp
    val headerHeight = 62.dp * fontScale
    val context = LocalContext.current
    var initialZoom by remember { mutableFloatStateOf(TimetableZoomSettings.initial(context)) }
    val transform = rememberSaveable(saver = GridTransform.Saver) { GridTransform() }
    Column(Modifier.fillMaxSize()) {
        PageHeader("一周课表", data.semester.school.ifBlank { data.semester.name }) {
            IconButton(onSettings) { Icon(Icons.Outlined.Tune, "学期与作息设置") }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton({ if (week > 1) week-- }, enabled = week > 1) { Icon(Icons.Outlined.ChevronLeft, "上一周") }
            Box {
                TextButton({ showWeeks = true }) { Text("第 $week 周 ▾", fontWeight = FontWeight.Bold) }
                DropdownMenu(showWeeks, { showWeeks = false }, Modifier.heightIn(max = 340.dp)) {
                    DropdownMenuItem(text = { Text("设置当前周…", color = MaterialTheme.colorScheme.primary) },
                        leadingIcon = { Icon(Icons.Outlined.EditCalendar, null, tint = MaterialTheme.colorScheme.primary) },
                        onClick = { showWeeks = false; settingWeek = true })
                    HorizontalDivider()
                    (1..data.semester.weekCount).forEach { index ->
                        DropdownMenuItem(text = { Text("第 $index 周") }, onClick = { week = index; showWeeks = false })
                    }
                }
            }
            IconButton({ if (week < data.semester.weekCount) week++ }, enabled = week < data.semester.weekCount) {
                Icon(Icons.Outlined.ChevronRight, "下一周")
            }
            Spacer(Modifier.weight(1f))
            TextButton({ week = actualWeek.coerceIn(1, data.semester.weekCount) }) { Text("回到本周") }
        }
        if (actualWeek !in 1..data.semester.weekCount) Row(Modifier.padding(start = 22.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("今天不在学期内（按第一周周一 ${data.semester.firstMonday} 计算）", Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton({ settingWeek = true }) { Text("设置当前周") }
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            ZoomableGrid(transform, initialZoom, contentWidth = columnWidth * days, contentHeight = rowHeight * data.periods.size + 96.dp,
                sideWidth = timeWidth, headerHeight = headerHeight, modifier = Modifier.fillMaxSize(),
                corner = {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
                        Text("${monday.monthValue}月", style = MaterialTheme.typography.labelMedium)
                    }
                },
                header = {
                    Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                        repeat(days) { day ->
                            val date = monday.plusDays(day.toLong())
                            Column(Modifier.width(columnWidth).fillMaxHeight().padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(weekdayNames[day], style = MaterialTheme.typography.labelMedium)
                                Text("${date.dayOfMonth}", fontWeight = FontWeight.Bold,
                                    color = if (date == today) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                },
                side = {
                    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                        data.periods.forEach { period ->
                            Column(Modifier.fillMaxWidth().height(rowHeight).padding(top = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("${period.number}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                if (prefs.showPeriodTimes) {
                                    Text(period.start, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(period.end, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }) {
                Row {
                    repeat(days) { day ->
                        val date = monday.plusDays(day.toLong())
                        val sessions = ScheduleCalculator.onDate(data, date)
                        Box(Modifier.width(columnWidth).height(rowHeight * data.periods.size)) {
                            if (prefs.showGridLines) Column {
                                data.periods.forEach { _ ->
                                    Box(Modifier.fillMaxWidth().height(rowHeight).border(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)))
                                }
                            }
                            if (prefs.showInactive) ScheduleCalculator.blocks(ScheduleCalculator.inactiveOnDate(data, date)).forEach { block ->
                                val first = block.sessions.first()
                                Card(onClick = { if (block.sessions.size == 1) onCourse(first.course.id) else overlapping = block.sessions },
                                    modifier = Modifier.offset(y = rowHeight * (block.startPeriod - 1)).width(columnWidth)
                                        .height(rowHeight * (block.endPeriod - block.startPeriod + 1)).padding(3.dp),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))) {
                                    Column(Modifier.padding(9.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text("[非本周]", style = MaterialTheme.typography.labelSmall)
                                        Text(block.sessions.joinToString(" / ") { it.course.name }, style = MaterialTheme.typography.bodySmall,
                                            maxLines = 3, overflow = TextOverflow.Ellipsis)
                                        if (block.sessions.size == 1) Text(WeekPattern.format(first.meeting.weeks) + " 周",
                                            style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                            ScheduleCalculator.blocks(sessions).forEach { block ->
                                val first = block.sessions.first()
                                val tint = courseColor(first.course.color)
                                val solid = prefs.cardStyle == CardStyle.SOLID
                                // Solid cards use the course colour itself, with white or dark text depending on its brightness.
                                val solidText = if (tint.luminance() > 0.55f) Color(0xFF1C1B22) else Color.White
                                Card(onClick = { if (block.sessions.size == 1) onCourse(first.course.id) else overlapping = block.sessions },
                                    modifier = Modifier.offset(y = rowHeight * (block.startPeriod - 1)).width(columnWidth)
                                        .height(rowHeight * (block.endPeriod - block.startPeriod + 1)).padding(3.dp),
                                    colors = if (solid) CardDefaults.cardColors(containerColor = tint, contentColor = solidText)
                                        else CardDefaults.cardColors(containerColor = tint.copy(alpha = 0.17f))) {
                                    Column(Modifier.padding(9.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        if (block.sessions.size > 1) Text("重叠 ${block.sessions.size} 门 · 点击查看", style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.error)
                                        Text(block.sessions.joinToString(" / ") { it.course.name }, fontWeight = FontWeight.SemiBold,
                                            style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
                                        if (block.sessions.size == 1) {
                                            if (prefs.showLocation && first.meeting.location.isNotBlank()) Text(first.meeting.location,
                                                style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                            if (prefs.showTeacher && first.course.teacher.isNotBlank()) Text(first.course.teacher,
                                                style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text("${block.startPeriod}–${block.endPeriod} 节", style = MaterialTheme.typography.labelSmall,
                                                color = if (solid) solidText.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            ZoomControls(transform, initialZoom, { value ->
                initialZoom = value
                TimetableZoomSettings.save(context, value)
                model.notify("初始大小已设为 ${TimetableZoomSettings.label(value)}")
            }, Modifier.align(Alignment.BottomStart).padding(12.dp))
        }
    }
    if (settingWeek) CurrentWeekDialog(data, today, actualWeek, model.busy, { settingWeek = false }) { chosen ->
        model.setCurrentWeek(today, chosen) { settingWeek = false; week = chosen }
    }
    if (overlapping.isNotEmpty()) AlertDialog(onDismissRequest = { overlapping = emptyList() },
        title = { Text("该时段有多条安排") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                overlapping.forEach { session ->
                    TextButton({ onCourse(session.course.id); overlapping = emptyList() }) {
                        Text("${session.course.name}\n${session.meeting.startPeriod}–${session.meeting.endPeriod} 节 · ${session.meeting.location}")
                    }
                }
            }
        }, confirmButton = { TextButton({ overlapping = emptyList() }) { Text("关闭") } })
}

@Composable
private fun CurrentWeekDialog(data: AppData, today: LocalDate, actualWeek: Int, busy: Boolean, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    var chosen by remember { mutableIntStateOf(actualWeek.coerceIn(1, data.semester.weekCount)) }
    val monday = ScheduleCalculator.firstMondayFor(today, chosen)
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("设置当前周") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("今天是 ${today.monthValue} 月 ${today.dayOfMonth} 日（${weekdayNames[today.dayOfWeek.value - 1]}），属于第几教学周？")
            ChoiceField("今天是", "第 $chosen 周", (1..data.semester.weekCount).map { it to "第 $it 周" }) { chosen = it }
            Text("第一周周一将设为 $monday（原为 ${data.semester.firstMonday}）。课程的周次不会改变。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }, confirmButton = { TextButton({ onConfirm(chosen) }, enabled = !busy) { Text("确定") } },
        dismissButton = { TextButton(onDismiss, enabled = !busy) { Text("取消") } })
}
