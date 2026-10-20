package com.coursatrix.app.ui.timetable

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.coursatrix.app.ui.CoursatrixViewModel
import com.coursatrix.app.ui.components.*
import com.coursatrix.core.model.*
import com.coursatrix.core.validation.Validation
import com.coursatrix.core.validation.WeekPattern

private data class MeetingDraft(val id: String = newId(), val day: Int = 1, val start: Int = 1,
    val end: Int = 2, val weeks: String, val location: String = "")

@Composable
fun CourseEditor(data: AppData, existing: Course?, model: CoursatrixViewModel, onDismiss: () -> Unit) {
    val id = remember(existing?.id) { existing?.id ?: newId() }
    var name by rememberSaveable(id) { mutableStateOf(existing?.name ?: "") }
    var teacher by rememberSaveable(id) { mutableStateOf(existing?.teacher ?: "") }
    var color by rememberSaveable(id) { mutableStateOf(existing?.color ?: courseColors[data.courses.size % courseColors.size]) }
    var note by rememberSaveable(id) { mutableStateOf(existing?.note ?: "") }
    var error by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf(false) }
    val drafts = remember(id) {
        mutableStateListOf<MeetingDraft>().apply {
            addAll(data.meetings.filter { it.courseId == id }.map {
                MeetingDraft(it.id, it.weekday, it.startPeriod, it.endPeriod, WeekPattern.format(it.weeks), it.location)
            }.ifEmpty { listOf(MeetingDraft(end = minOf(2, data.periods.size), weeks = "1-${data.semester.weekCount}")) })
        }
    }
    FormDialog(if (existing == null) "添加课程" else "编辑课程", model.busy, onDismiss, onSave = {
        try {
            val course = Course(id, data.semester.id, name.trim(), teacher.trim(), color, note)
            val meetings = drafts.map { d -> Meeting(d.id, id, d.day, d.start, d.end,
                WeekPattern.parse(d.weeks, data.semester.weekCount), d.location.trim()) }
            val candidate = data.copy(courses = data.courses.filterNot { it.id == id } + course,
                meetings = data.meetings.filterNot { it.courseId == id } + meetings)
            Validation.requireValid(candidate)
            model.saveCourse(course, meetings, onDismiss)
        } catch (e: IllegalArgumentException) { error = e.message }
    }) {
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Field(name, { name = it }, "课程名称 *")
        Field(teacher, { teacher = it }, "任课教师")
        SectionTitle("课程颜色")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            courseColors.forEach { hex ->
                FilterChip(color == hex, { color = hex }, label = {
                    Box(Modifier.size(22.dp).background(courseColor(hex), MaterialTheme.shapes.small))
                })
            }
        }
        Field(note, { note = it }, "私人备注", "分享课表文件不包含备注；完整备份会保留。", singleLine = false)
        SectionTitle("上课安排")
        drafts.forEachIndexed { index, d ->
            key(d.id) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("安排 ${index + 1}", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                            IconButton({ drafts.removeAt(index) }, enabled = drafts.size > 1) {
                                Icon(Icons.Outlined.DeleteOutline, "移除这条安排")
                            }
                        }
                        ChoiceField("星期", weekdayNames[d.day - 1], weekdayNames.mapIndexed { i, title -> i + 1 to title }) {
                            drafts[index] = d.copy(day = it)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(Modifier.weight(1f)) {
                                ChoiceField("开始节次", "第 ${d.start} 节", data.periods.map { it.number to "第 ${it.number} 节 · ${it.start}" }) {
                                    drafts[index] = d.copy(start = it)
                                }
                            }
                            Box(Modifier.weight(1f)) {
                                ChoiceField("结束节次", "第 ${d.end} 节", data.periods.map { it.number to "第 ${it.number} 节 · ${it.end}" }) {
                                    drafts[index] = d.copy(end = it)
                                }
                            }
                        }
                        Field(d.location, { drafts[index] = d.copy(location = it) }, "上课教室 / 地点")
                        Field(d.weeks, { drafts[index] = d.copy(weeks = it) }, "上课周次 *", "例如 1-16、1,3,5、1-16单、2-16双；也可手动修改。")
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("全部周" to "1-${data.semester.weekCount}", "单周" to "1-${data.semester.weekCount}单",
                                "双周" to "1-${data.semester.weekCount}双").forEach { (label, value) ->
                                AssistChip({ drafts[index] = d.copy(weeks = value) }, label = { Text(label) })
                            }
                        }
                    }
                }
            }
        }
        OutlinedButton({ drafts += MeetingDraft(end = minOf(2, data.periods.size), weeks = "1-${data.semester.weekCount}") },
            Modifier.fillMaxWidth(), enabled = drafts.size < 100) {
            Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(6.dp)); Text("添加另一条上课安排")
        }
        if (existing != null) TextButton({ deleting = true }, Modifier.fillMaxWidth()) {
            Text("删除这门课程", color = MaterialTheme.colorScheme.error)
        }
    }
    if (deleting) ConfirmDialog("删除课程？", "该课程的全部上课安排会被删除。关联的计划保留，但会解除课程关联。",
        { deleting = false }, { deleting = false; model.deleteCourse(id, onDismiss) }, "删除")
}

@Composable
fun CourseDetails(course: Course, data: AppData, onDismiss: () -> Unit, onEdit: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(course.name) }, text = {
        Column(Modifier.heightIn(max = 450.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("教师：${course.teacher.ifBlank { "未填写" }}")
            data.meetings.filter { it.courseId == course.id }.sortedWith(compareBy({ it.weekday }, { it.startPeriod })).forEach { m ->
                HorizontalDivider()
                Text("${weekdayNames[m.weekday - 1]} · 第 ${m.startPeriod}–${m.endPeriod} 节", style = MaterialTheme.typography.titleSmall)
                Text("${data.periods[m.startPeriod - 1].start}–${data.periods[m.endPeriod - 1].end}")
                Text("教室：${m.location.ifBlank { "未填写" }}")
                Text("第 ${WeekPattern.format(m.weeks)} 周")
            }
            if (course.note.isNotBlank()) { HorizontalDivider(); Text("备注：${course.note}") }
        }
    }, confirmButton = { TextButton(onEdit) { Text("编辑课程") } }, dismissButton = { TextButton(onDismiss) { Text("关闭") } })
}
