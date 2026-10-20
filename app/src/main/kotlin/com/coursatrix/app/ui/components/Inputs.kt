package com.coursatrix.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@Composable
fun <T> ChoiceField(label: String, value: String, choices: List<Pair<T, String>>, onSelect: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton({ expanded = true }, Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                Text(label, style = MaterialTheme.typography.labelSmall)
                Text(value, style = MaterialTheme.typography.bodyLarge)
            }
            Icon(Icons.Outlined.ExpandMore, null)
        }
        DropdownMenu(expanded, { expanded = false }, Modifier.heightIn(max = 340.dp)) {
            choices.forEach { (key, text) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { onSelect(key); expanded = false })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(value: String, onChange: (String) -> Unit, label: String, hint: String? = null) {
    var picker by remember { mutableStateOf(false) }
    OutlinedTextField(value, onChange, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true,
        supportingText = hint?.let { { Text(it) } }, shape = MaterialTheme.shapes.medium,
        trailingIcon = { IconButton({ picker = true }) { Icon(Icons.Outlined.CalendarMonth, "选择日期") } })
    if (picker) {
        val millis = runCatching {
            LocalDate.parse(value).takeIf { it.year in 1900..2100 }?.atStartOfDay()?.toInstant(ZoneOffset.UTC)?.toEpochMilli()
        }.getOrNull()
        val state = rememberDatePickerState(initialSelectedDateMillis = millis)
        DatePickerDialog(onDismissRequest = { picker = false }, confirmButton = {
            TextButton({
                state.selectedDateMillis?.let { onChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString()) }
                picker = false
            }, enabled = state.selectedDateMillis != null) { Text("确定") }
        }, dismissButton = { TextButton({ picker = false }) { Text("取消") } }) { DatePicker(state) }
    }
}
