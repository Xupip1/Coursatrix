package com.coursatrix.app.ui.plan

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.coursatrix.app.ui.CoursatrixViewModel
import com.coursatrix.app.ui.components.*
import com.coursatrix.core.model.*
import com.coursatrix.core.validation.Validation

@Composable
fun TaskEditor(data: AppData, existing: PlanTask?, model: CoursatrixViewModel, onDismiss: () -> Unit) {
    val id = remember(existing?.id) { existing?.id ?: newId() }
    var title by rememberSaveable(id) { mutableStateOf(existing?.title ?: "") }
    var kind by rememberSaveable(id) { mutableStateOf(existing?.kind ?: TaskKind.TASK) }
    var dueDate by rememberSaveable(id) { mutableStateOf(existing?.dueDate ?: "") }
    var note by rememberSaveable(id) { mutableStateOf(existing?.note ?: "") }
    var courseId by rememberSaveable(id) { mutableStateOf(existing?.courseId) }
    var error by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf(false) }
    FormDialog(if (existing == null) "新建计划" else "编辑计划", model.busy, onDismiss, {
        val task = PlanTask(id, title.trim(), kind, dueDate.trim().ifBlank { null }, existing?.completed ?: false, courseId, note)
        val candidate = data.copy(tasks = data.tasks.filterNot { it.id == id } + task)
        val errors = Validation.errors(candidate)
        if (errors.isEmpty()) model.saveTask(task, onDismiss) else error = errors.joinToString("\n")
    }) {
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Field(title, { title = it }, "计划标题 *")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TaskKind.entries.forEach { value -> FilterChip(kind == value, { kind = value }, label = { Text(value.label) }) }
        }
        DateField(dueDate, { dueDate = it }, "截止日期（可选）", "YYYY-MM-DD；留空表示未设截止日期。")
        if (dueDate.isNotBlank()) TextButton({ dueDate = "" }) { Text("清除截止日期") }
        ChoiceField("关联课程", data.courses.find { it.id == courseId }?.name ?: "不关联课程",
            listOf(null to "不关联课程") + data.courses.map { it.id to it.name }) { courseId = it }
        Field(note, { note = it }, "备注", singleLine = false)
        if (existing != null) TextButton({ deleting = true }, Modifier.fillMaxWidth()) {
            Text("删除这条计划", color = MaterialTheme.colorScheme.error)
        }
    }
    if (deleting) ConfirmDialog("删除计划？", "删除后无法撤销。", { deleting = false },
        { deleting = false; model.deleteTask(id, onDismiss) }, "删除")
}
