package com.coursatrix.app.ui.profile

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.coursatrix.app.ui.ImportPreview
import com.coursatrix.app.ui.components.*
import com.coursatrix.core.model.AppData
import com.coursatrix.core.model.weekdayNames
import com.coursatrix.core.transfer.ContentKind
import com.coursatrix.core.transfer.layout.DraftEntry
import com.coursatrix.core.transfer.layout.DraftImporter
import com.coursatrix.core.transfer.layout.DraftTimetable
import com.coursatrix.core.validation.WeekPattern

/** Editable copy of a recognised entry; [weeks] blank means "use the default for entries without weeks". */
private data class EditableEntry(
    val name: String, val teacher: String, val location: String, val weekday: Int, val start: Int, val end: Int,
    val weeks: String, val issues: List<String>, val expanded: Boolean,
)

/**
 * Lets the user check and fix what was recognised from a PDF or screenshot before anything is imported.
 * Entries with problems are highlighted and opened for editing; [onNext] continues to the normal import preview.
 */
@Composable
fun DraftReviewDialog(source: String, draft: DraftTimetable, data: AppData, busy: Boolean, onDismiss: () -> Unit,
                      onNext: (ImportPreview) -> Unit) {
    val entries = remember(draft) {
        mutableStateListOf(*draft.entries.map { e ->
            EditableEntry(e.name, e.teacher, e.location, e.weekday, e.startPeriod, e.endPeriod,
                e.weeks?.let(WeekPattern::format).orEmpty(), e.issues, expanded = e.issues.any { "截断" in it || "缺少" in it })
        }.toTypedArray())
    }
    var defaultWeeks by remember { mutableStateOf("1-${data.semester.weekCount}") }
    var useDetectedPeriods by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val periods = if (useDetectedPeriods && draft.periods != null) draft.periods!! else data.periods
    val needsReview = entries.count { it.issues.isNotEmpty() }

    fun next() {
        val problems = mutableListOf<String>()
        val built = entries.mapNotNull { e ->
            val label = "${weekdayNames[e.weekday - 1]} 第 ${e.start}–${e.end} 节「${e.name.ifBlank { "未命名" }}」"
            val weeks = runCatching { WeekPattern.parse(e.weeks.ifBlank { defaultWeeks }, data.semester.weekCount) }
                .getOrElse { problems += "$label：${it.message}"; null }
            if (e.name.isBlank()) problems += "$label：请填写课程名称"
            if (e.end > periods.size || e.start > e.end) problems += "$label：节次超出作息的 ${periods.size} 节或顺序错误"
            weeks?.let { DraftEntry(e.name, e.teacher, e.location, e.weekday, e.start, e.end, it) }
        }
        if (problems.isNotEmpty()) { error = problems.take(8).joinToString("\n"); return }
        val base = if (useDetectedPeriods && draft.periods != null) data.copy(periods = draft.periods!!) else data
        runCatching { DraftImporter.build(built, base) }
            .onSuccess { onNext(ImportPreview(it, if (base === data) null else ContentKind.TIMETABLE, source = source)) }
            .onFailure { error = it.message }
    }

    FormDialog("核对识别结果", busy, onDismiss, ::next, saveLabel = "下一步", canSave = entries.isNotEmpty()) {
        Text("从$source 识别出 ${entries.size} 条上课安排" + if (needsReview > 0) "，其中 $needsReview 条需要核对（红字）。" else "。",
            fontWeight = FontWeight.SemiBold)
        draft.notes.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Text("识别结果可能有误，请对照原文件检查。点击任意一条可修改。", style = MaterialTheme.typography.bodySmall)
        draft.periods?.let { found ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(useDetectedPeriods, { useDetectedPeriods = it })
                Text("同时使用识别出的 ${found.size} 节作息时间（${found.first().start} 起；将替换课表，不能合并）",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        if (entries.any { it.weeks.isBlank() }) Field(defaultWeeks, { defaultWeeks = it }, "没有周次的课程统一设为",
            "例如 1-16、1-16单、1-8,10-16；也可在下方逐条填写")
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        entries.forEachIndexed { index, entry ->
            EntryCard(entry, periods.size, { entries[index] = it }, { entries.removeAt(index) })
        }
        if (entries.isEmpty()) Text("已没有可导入的课程。", color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun EntryCard(entry: EditableEntry, periodCount: Int, onChange: (EditableEntry) -> Unit, onDelete: () -> Unit) {
    // Any edit counts as reviewed, so the warnings disappear.
    fun edit(value: EditableEntry) = onChange(value.copy(issues = emptyList()))
    Card(onClick = { onChange(entry.copy(expanded = !entry.expanded)) }, modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = if (entry.issues.isEmpty()) MaterialTheme.colorScheme.surface
            else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f))) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("${weekdayNames[entry.weekday - 1]} 第 ${entry.start}–${entry.end} 节 · ${entry.name.ifBlank { "（未命名）" }}",
                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(listOf(entry.teacher, entry.location, if (entry.weeks.isBlank()) "周次：默认" else "${entry.weeks} 周")
                .filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            entry.issues.forEach { Text("⚠ $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            if (entry.expanded) {
                Field(entry.name, { edit(entry.copy(name = it)) }, "课程名称")
                Field(entry.teacher, { edit(entry.copy(teacher = it)) }, "教师（可空）")
                Field(entry.location, { edit(entry.copy(location = it)) }, "地点（可空）")
                ChoiceField("星期", weekdayNames[entry.weekday - 1], weekdayNames.mapIndexed { i, d -> i + 1 to d }) {
                    edit(entry.copy(weekday = it))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f)) {
                        ChoiceField("开始节", "第 ${entry.start} 节", (1..periodCount).map { it to "第 $it 节" }) {
                            edit(entry.copy(start = it, end = maxOf(it, entry.end)))
                        }
                    }
                    Box(Modifier.weight(1f)) {
                        ChoiceField("结束节", "第 ${entry.end} 节", (entry.start..periodCount).map { it to "第 $it 节" }) {
                            edit(entry.copy(end = it))
                        }
                    }
                }
                Field(entry.weeks, { edit(entry.copy(weeks = it)) }, "周次（留空则用默认）", "例如 5-16、6-16双")
                TextButton(onDelete) { Text("删除这一条", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}
