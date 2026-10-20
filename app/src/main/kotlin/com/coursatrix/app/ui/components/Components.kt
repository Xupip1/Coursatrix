package com.coursatrix.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.toColorInt
import com.coursatrix.core.model.PlanTask

fun courseColor(hex: String): Color = Color(hex.toColorInt())

@Composable
fun PageHeader(title: String, subtitle: String, action: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("COURSATRIX", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold)
            Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        action()
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, detail: String, button: String? = null, onClick: () -> Unit = {}) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.primaryContainer) {
                Icon(icon, null, Modifier.padding(18.dp).size(30.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (button != null) FilledTonalButton(onClick) { Text(button) }
        }
    }
}

@Composable
fun SectionTitle(title: String, detail: String? = null) {
    Column(Modifier.padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun FormDialog(title: String, busy: Boolean, onDismiss: () -> Unit, onSave: () -> Unit,
               saveLabel: String = "保存", canSave: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.safeDrawingPadding().imePadding().widthIn(max = 760.dp)) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss, enabled = !busy) { Icon(Icons.Outlined.Close, "关闭") }
                    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Button(onClick = onSave, enabled = !busy && canSave) { Text(if (busy) "处理中…" else saveLabel) }
                }
                HorizontalDivider()
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
            }
        }
    }
}

@Composable
fun Field(value: String, onChange: (String) -> Unit, label: String, hint: String? = null, singleLine: Boolean = true) {
    OutlinedTextField(value, onChange, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = singleLine,
        minLines = if (singleLine) 1 else 3, supportingText = if (hint == null) null else ({ Text(hint) }),
        shape = MaterialTheme.shapes.medium)
}

@Composable
fun ConfirmDialog(title: String, detail: String, onDismiss: () -> Unit, onConfirm: () -> Unit, confirmLabel: String = "确认") {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(detail) },
        confirmButton = { TextButton(onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onDismiss) { Text("取消") } })
}

@Composable
fun TaskCard(task: PlanTask, courseName: String?, onToggle: () -> Unit, onEdit: () -> Unit) {
    Card(onClick = onEdit, modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = task.completed, onCheckedChange = { onToggle() })
            Column(Modifier.weight(1f).padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(task.title, style = MaterialTheme.typography.titleSmall,
                    textDecoration = if (task.completed) TextDecoration.LineThrough else null)
                Text(listOfNotNull(task.kind.label, task.dueDate?.let { "截止 $it" }, courseName).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
