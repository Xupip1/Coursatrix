package com.coursatrix.app.ui.profile

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.coursatrix.app.ui.CoursatrixViewModel
import com.coursatrix.app.ui.components.*
import com.coursatrix.core.model.*
import com.coursatrix.core.validation.Limits
import com.coursatrix.core.validation.Validation
import java.time.LocalTime

@Composable
fun SettingsEditor(data: AppData, model: CoursatrixViewModel, onDismiss: () -> Unit) {
    var school by rememberSaveable { mutableStateOf(data.semester.school) }
    var name by rememberSaveable { mutableStateOf(data.semester.name) }
    var monday by rememberSaveable { mutableStateOf(data.semester.firstMonday) }
    var weeks by rememberSaveable { mutableStateOf(data.semester.weekCount.toString()) }
    val periods = remember { mutableStateListOf<Period>().apply { addAll(data.periods) } }
    var error by remember { mutableStateOf<String?>(null) }
    FormDialog("学期与作息", model.busy, onDismiss, {
        val count = weeks.toIntOrNull()
        if (count == null) error = "学期总周数应为整数"
        else {
            val semester = data.semester.copy(school = school.trim(), name = name.trim(), firstMonday = monday.trim(), weekCount = count)
            val errors = Validation.errors(data.copy(semester = semester, periods = periods.toList()))
            if (errors.isNotEmpty()) error = errors.joinToString("\n")
            else model.saveSettings(semester, periods.toList(), data.preferences, onDismiss)
        }
    }) {
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Field(school, { school = it }, "学校名称（可选）")
        Field(name, { name = it }, "学期名称 *")
        DateField(monday, { monday = it }, "第一周的周一 *")
        Field(weeks, { weeks = it }, "学期总周数 *（1–${Limits.WEEKS}）")
        SectionTitle("每天的作息")
        periods.forEachIndexed { index, period ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${index + 1}", Modifier.width(22.dp), style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(period.start, { periods[index] = period.copy(start = it) }, Modifier.weight(1f),
                    label = { Text("开始") }, singleLine = true, shape = MaterialTheme.shapes.medium)
                Text("–")
                OutlinedTextField(period.end, { periods[index] = period.copy(end = it) }, Modifier.weight(1f),
                    label = { Text("结束") }, singleLine = true, shape = MaterialTheme.shapes.medium)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton({
                try {
                    val lastEnd = LocalTime.parse(periods.last().end)
                    val start = lastEnd.plusMinutes(10)
                    val end = start.plusMinutes(45)
                    require(start > lastEnd && end > start) { "当天已没有足够时间添加新节次，请先调整现有作息" }
                    periods += Period(periods.size + 1, start.toString(), end.toString())
                } catch (e: Exception) { error = e.message ?: "请先填写正确的时间" }
            }, Modifier.weight(1f), enabled = periods.size < Limits.PERIODS) { Text("增加一节") }
            OutlinedButton({
                if (data.meetings.any { it.endPeriod >= periods.size }) error = "已有课程使用第 ${periods.size} 节，请先修改相关课程再减少节数"
                else periods.removeAt(periods.lastIndex)
            }, Modifier.weight(1f), enabled = periods.size > 1) { Text("减少末节") }
        }
    }
}
