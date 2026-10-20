package com.coursatrix.app.ui.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.coursatrix.app.ui.components.*
import com.coursatrix.core.model.AppData
import com.coursatrix.core.schedule.ScheduleCalculator
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val dayLabels = listOf("今天", "明天", "后天")

@Composable
fun AgendaScreen(data: AppData, now: LocalDateTime, onCourse: (String) -> Unit,
                 onAddCourse: () -> Unit, onSettings: () -> Unit) {
    // 0 = today, 1 = tomorrow, 2 = the day after tomorrow.
    var offset by rememberSaveable { mutableIntStateOf(0) }
    val dayLabel = dayLabels[offset]
    val date = now.toLocalDate().plusDays(offset.toLong())
    val sessions = ScheduleCalculator.onDate(data, date)
    val week = ScheduleCalculator.weekOn(data.semester, date)
    val next = sessions.firstOrNull { offset > 0 || it.end > now.toLocalTime() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { PageHeader("让今天，从容一点", data.semester.school.ifBlank { "你的课程与计划，都在这里" }) }
        item {
            Row(Modifier.padding(horizontal = 22.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                dayLabels.forEachIndexed { index, label ->
                    FilterChip(offset == index, { offset = index }, label = { Text(label) })
                }
            }
        }
        item {
            Card(Modifier.padding(horizontal = 22.dp).fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(date.format(DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.SIMPLIFIED_CHINESE)),
                        style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(if (week in 1..data.semester.weekCount) "${data.semester.name} · 第 $week 周" else "当前日期不在学期内",
                        style = MaterialTheme.typography.bodyMedium)
                    if (next != null) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.12f))
                        Text(if (offset == 0 && next.start <= now.toLocalTime()) "正在上课" else "下一节课",
                            style = MaterialTheme.typography.labelMedium)
                        Text(next.course.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text("${next.start}–${next.end}  ·  ${next.meeting.location.ifBlank { "未填写教室" }}")
                    } else Text(if (sessions.isEmpty()) "留点时间给自己，也给新的可能。" else "今天的课程已经结束，辛苦了。")
                }
            }
        }
        if (data.courses.isEmpty()) item {
            Column(Modifier.padding(horizontal = 22.dp)) {
                TextButton(onSettings) { Text("首次使用：先设置开学日期和作息时间 →") }
            }
        }
        item { Box(Modifier.padding(horizontal = 22.dp)) { SectionTitle("${dayLabel}的课程") } }
        if (sessions.isEmpty()) item {
            Box(Modifier.padding(horizontal = 22.dp)) {
                EmptyState(Icons.Outlined.CalendarToday, "没有课程安排", if (week !in 1..data.semester.weekCount)
                    "请检查学期的第一周日期，或在课表中查看其他周。" else "有新课程？添加后就会显示在这里。", "添加课程", onAddCourse)
            }
        }
        items(sessions, key = { it.meeting.id }) { session ->
            Card(onClick = { onCourse(session.course.id) }, modifier = Modifier.padding(horizontal = 22.dp).fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Row(Modifier.padding(18.dp), horizontalArrangement = Arrangement.spacedBy(15.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(4.dp).height(58.dp).background(courseColor(session.course.color), MaterialTheme.shapes.small))
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(session.start.toString(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(session.end.toString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(session.course.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(session.meeting.location.ifBlank { "未填写教室" }, style = MaterialTheme.typography.bodyMedium)
                        Text(session.course.teacher.ifBlank { "未填写教师" }, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
