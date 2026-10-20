package com.coursatrix.app.ui.plan

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.coursatrix.app.ui.components.*
import com.coursatrix.core.model.AppData

@Composable
fun PlanScreen(data: AppData, onEdit: (String) -> Unit, onToggle: (String) -> Unit, onAdd: () -> Unit) {
    var filter by rememberSaveable { mutableIntStateOf(0) }
    val tasks = data.tasks.filter { when (filter) { 0 -> !it.completed; 1 -> it.completed; else -> true } }
        .sortedWith(compareBy({ it.completed }, { it.dueDate ?: "9999-12-31" }, { it.title }))
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 110.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { PageHeader("把想做的，慢慢完成", "作业、考试与学习任务，有条不紊") }
        item {
            Row(Modifier.padding(horizontal = 22.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("待完成", "已完成", "全部").forEachIndexed { index, title ->
                    FilterChip(filter == index, { filter = index }, label = { Text(title) })
                }
            }
        }
        if (tasks.isEmpty()) item {
            Box(Modifier.padding(horizontal = 22.dp)) {
                EmptyState(Icons.Outlined.Checklist, "这里还没有计划", "记下下一件想完成的事。可以关联课程，也可以只属于自己。", "新建计划", onAdd)
            }
        }
        items(tasks, key = { it.id }) { task ->
            Box(Modifier.padding(horizontal = 22.dp)) {
                TaskCard(task, data.courses.find { it.id == task.courseId }?.name, { onToggle(task.id) }, { onEdit(task.id) })
            }
        }
    }
}
