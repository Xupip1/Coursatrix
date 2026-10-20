package com.coursatrix.app.ui.profile

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.coursatrix.app.importing.LayoutFiles
import com.coursatrix.app.importing.PdfSource
import com.coursatrix.core.transfer.layout.DraftTimetable
import com.coursatrix.core.transfer.layout.GridReconstructor
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.coursatrix.app.ui.CoursatrixViewModel
import com.coursatrix.app.ui.ImportPreview
import com.coursatrix.app.ui.components.*
import com.coursatrix.core.model.AppData
import com.coursatrix.core.schedule.ScheduleCalculator
import com.coursatrix.core.transfer.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

private data class ExportRequest(val name: String, val text: String)

@Composable
fun TransferPanel(data: AppData, model: CoursatrixViewModel) {
    val context = LocalContext.current
    var preview by remember { mutableStateOf<ImportPreview?>(null) }
    var request by remember { mutableStateOf<ExportRequest?>(null) }
    var hideTeachers by remember { mutableStateOf(false) }
    var hideLocations by remember { mutableStateOf(false) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val pending = request
        request = null
        if (uri != null && pending != null) model.action {
            writeDocument(context, uri, pending.text)
            model.notify("已导出 ${pending.name}")
        }
    }
    val saveCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        val pending = request
        request = null
        if (uri != null && pending != null) model.action {
            writeDocument(context, uri, pending.text)
            model.notify("CSV 导出完成（包含课程备注，请谨慎分享）")
        }
    }
    var draft by remember { mutableStateOf<DraftTimetable?>(null) }
    fun load(uri: Uri) = model.action {
        val bytes = readDocument(context, uri)
        // A PDF has no table structure: rebuild the grid from text positions, then let the user review it.
        if (LayoutFiles.isPdf(bytes)) {
            draft = withContext(Dispatchers.Default) { GridReconstructor.reconstruct(PdfSource.read(context, bytes)) }
        } else preview = withContext(Dispatchers.Default) {
            if (SchoolXlsImporter.isSpreadsheet(bytes)) {
                val result = SchoolXlsImporter.decode(bytes, data)
                return@withContext ImportPreview(result.data, ContentKind.TIMETABLE, school = result)
            }
            val text = decodeText(bytes)
            if (text.trimStart(0xFEFF.toChar(), ' ', '\n', '\r', '\t').startsWith("{")) {
                val envelope = BackupCodec.decode(text)
                ImportPreview(envelope.data, envelope.kind)
            } else {
                val parsed = CsvCodec.decode(text, data)
                ImportPreview(parsed.data, null, parsed.errors, parsed.duplicates)
            }
        }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) load(uri) }
    fun export(kind: ContentKind) {
        model.action {
            val text = withContext(Dispatchers.Default) { BackupCodec.encode(data, kind, hideTeachers, hideLocations) }
            val name = "Coursatrix-${if (kind == ContentKind.BACKUP) "backup" else "timetable"}-${LocalDate.now()}.coursatrix"
            request = ExportRequest(name, text)
            save.launch(name)
        }
    }
    SectionTitle("导入与导出")
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Button({ open.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth(), enabled = !model.busy) {
                Text("导入文件 / 恢复备份")
            }
            HorizontalDivider()
            OutlinedButton({ export(ContentKind.BACKUP) }, Modifier.fillMaxWidth(), enabled = !model.busy) { Text("导出完整备份") }
            HorizontalDivider()
            Row { Checkbox(hideTeachers, { hideTeachers = it }); Text("分享时隐藏教师", Modifier.padding(top = 12.dp)) }
            Row { Checkbox(hideLocations, { hideLocations = it }); Text("分享时隐藏教室", Modifier.padding(top = 12.dp)) }
            OutlinedButton({ export(ContentKind.TIMETABLE) }, Modifier.fillMaxWidth(), enabled = !model.busy) { Text("导出分享课表") }
            HorizontalDivider()
            OutlinedButton({
                model.action {
                    val text = withContext(Dispatchers.Default) { CsvCodec.encode(data) }
                    val name = "Coursatrix-courses-${LocalDate.now()}.csv"
                    request = ExportRequest(name, text)
                    saveCsv.launch(name)
                }
            }, Modifier.fillMaxWidth(), enabled = !model.busy) { Text("导出 CSV（含课程备注）") }
        }
    }
    draft?.let { recognised ->
        DraftReviewDialog("PDF", recognised, data, model.busy, { draft = null }) { draft = null; preview = it }
    }
    preview?.let { loaded -> ImportPreviewDialog(loaded, data, model) { preview = null } }
}

@Composable
private fun ImportPreviewDialog(loaded: ImportPreview, current: AppData, model: CoursatrixViewModel, onDismiss: () -> Unit) {
    val today = remember { LocalDate.now() }
    val weekCount = loaded.data.semester.weekCount
    // School Excel files carry no semester start; the user states which teaching week today is.
    var week by remember { mutableIntStateOf(ScheduleCalculator.weekOn(loaded.data.semester, today).coerceIn(1, weekCount)) }
    val preview = remember(loaded, week) {
        if (loaded.school == null) loaded else loaded.copy(data = loaded.data.copy(semester = loaded.data.semester.copy(
            firstMonday = ScheduleCalculator.firstMondayFor(today, week).toString())))
    }
    var merge by remember { mutableStateOf(true) }
    var confirming by remember { mutableStateOf(false) }
    val candidate = remember(preview, current, merge) { runCatching {
        when (preview.kind) {
            ContentKind.BACKUP -> preview.data
            ContentKind.TIMETABLE -> ImportPlanner.replaceTimetable(current, preview.data)
            null -> if (merge) ImportPlanner.mergeCsv(current, preview.data) else ImportPlanner.replaceTimetable(current, preview.data)
        }
    } }
    val conflicts = candidate.getOrNull()?.let { ScheduleCalculator.conflicts(it.meetings) }.orEmpty()
    FormDialog("导入预览", model.busy, onDismiss, { confirming = true }, saveLabel = "确认导入",
        canSave = preview.errors.isEmpty() && candidate.isSuccess) {
        preview.school?.let { school ->
            Text("学校 Excel 课表：将替换学期作息与课程，保留本地计划。同名同教师的课程会沿用原颜色、备注和计划关联" +
                "（本次 ${school.reusedCourses} 门）。")
            Text(if (school.periodsFromFile) "作息时间取自表格，共 ${preview.data.periods.size} 节。"
                else "表格没有「时间」列，沿用当前作息的 ${preview.data.periods.size} 节，请核对。")
            ChoiceField("今天（$today）是第几教学周？", "第 $week 周", (1..weekCount).map { it to "第 $it 周" }) { week = it }
            Text("表格中最早的课程从第 ${preview.data.meetings.minOf { it.weeks.min() }} 周开始。若不确定，请查看学校校历；导入后也可在课表页随时调整。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } ?: Text(when (preview.kind) {
            ContentKind.BACKUP -> "完整备份：将替换本地全部数据，包括计划与设置。"
            ContentKind.TIMETABLE -> if (preview.source != null) "${preview.source}：将使用识别出的作息并替换课程，保留本地计划。"
                else "分享课表：将替换学期、作息与课程。保留本地计划，失效的课程关联会解除。"
            null -> "${preview.source ?: "CSV"}：使用当前学期与作息，只导入课程和上课安排。"
        }, color = if (preview.kind == ContentKind.BACKUP) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        Text("学期：${preview.data.semester.name}\n第一周周一：${preview.data.semester.firstMonday}\n总周数：${preview.data.semester.weekCount}\n文件中：${preview.data.courses.size} 门课程，${preview.data.meetings.size} 条上课安排")
        if (preview.kind == ContentKind.BACKUP) Text("文件中还包含 ${preview.data.tasks.size} 条计划")
        if (preview.duplicates > 0) Text("文件内已去除 ${preview.duplicates} 条完全重复安排")
        if (preview.kind == null) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilterChip(merge, { merge = true }, label = { Text("合并课程") })
            FilterChip(!merge, { merge = false }, label = { Text("替换课程") })
        }
        if (preview.kind == null && merge) Text("同名且同教师的课程合并；完全重复的安排跳过，保留原课程颜色与备注。")
        if (conflicts.isNotEmpty()) Text("导入后的课表有 ${if (conflicts.size >= 100) "至少 " else ""}${conflicts.size} 组时间重叠。不会自动覆盖，请导入后核对。",
            color = MaterialTheme.colorScheme.error)
        if (preview.errors.isNotEmpty()) {
            SectionTitle("发现错误，尚未写入", "请修正原文件后重新导入，最多显示 100 条错误。")
            preview.errors.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        candidate.exceptionOrNull()?.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        SectionTitle("课程列表")
        preview.data.courses.take(100).forEach { course ->
            Text("${course.name} · ${course.teacher.ifBlank { "未填写教师" }}", style = MaterialTheme.typography.bodyMedium)
        }
        if (preview.data.courses.size > 100) Text("仅预览前 100 门；确认后会导入全部有效课程。")
    }
    if (confirming) ConfirmDialog("确认写入本地数据？", if (preview.kind == ContentKind.BACKUP)
        "这会替换所有本地数据。建议先导出完整备份。操作完成后无法直接撤销。" else if (preview.kind != null || !merge)
        "这会替换当前课表。原课程关联可能解除，计划不会被删除。建议先导出备份。" else "将合并课程和安排，已有课程不会被删除。",
        { confirming = false }, { confirming = false; model.applyImport(preview, merge, onDismiss) })
}
