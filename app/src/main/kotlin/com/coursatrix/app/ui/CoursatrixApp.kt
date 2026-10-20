package com.coursatrix.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.coursatrix.app.ui.plan.PlanScreen
import com.coursatrix.app.ui.plan.TaskEditor
import com.coursatrix.app.ui.profile.ProfileScreen
import com.coursatrix.app.ui.profile.SettingsEditor
import com.coursatrix.app.ui.schedule.AgendaScreen
import com.coursatrix.app.ui.timetable.CourseDetails
import com.coursatrix.app.ui.timetable.CourseEditor
import com.coursatrix.app.ui.timetable.TimetableScreen
import com.coursatrix.core.model.AppData
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.time.LocalDateTime

@Composable
fun CoursatrixApp(model: CoursatrixViewModel, data: AppData?) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var detailsId by rememberSaveable { mutableStateOf<String?>(null) }
    var courseId by rememberSaveable { mutableStateOf<String?>(null) }
    var showCourse by rememberSaveable { mutableStateOf(false) }
    var taskId by rememberSaveable { mutableStateOf<String?>(null) }
    var showTask by rememberSaveable { mutableStateOf(false) }
    var settings by rememberSaveable { mutableStateOf(false) }
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) { while (isActive) { now = LocalDateTime.now(); delay(30_000) } }
    fun newCourse() { courseId = null; showCourse = true }
    fun newTask() { taskId = null; showTask = true }
    fun editTask(id: String) { taskId = id; showTask = true }
    val labels = listOf("日程", "课表", "计划", "我的")
    val icons = listOf(Icons.Outlined.Today, Icons.Outlined.CalendarViewWeek, Icons.Outlined.Checklist, Icons.Outlined.PersonOutline)
    Scaffold(containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                labels.forEachIndexed { index, label ->
                    NavigationBarItem(tab == index, { tab = index }, icon = { Icon(icons[index], null) }, label = { Text(label) })
                }
            }
        }, floatingActionButton = {
            if (data != null && (tab == 1 || tab == 2)) ExtendedFloatingActionButton(
                onClick = { if (tab == 1) newCourse() else newTask() }, icon = { Icon(Icons.Outlined.Add, null) },
                text = { Text(if (tab == 1) "添加课程" else "新建计划") })
        }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (data == null) Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Text("正在准备本地数据…")
            } else when (tab) {
                0 -> AgendaScreen(data, now, { detailsId = it }, ::newCourse) { settings = true }
                1 -> TimetableScreen(data, now.toLocalDate(), model, { detailsId = it }) { settings = true }
                2 -> PlanScreen(data, ::editTask, model::toggleTask, ::newTask)
                3 -> ProfileScreen(data, model) { settings = true }
            }
            if (model.busy) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
        }
    }
    if (data != null) {
        data.courses.find { it.id == detailsId }?.let { course ->
            CourseDetails(course, data, { detailsId = null }, { courseId = course.id; detailsId = null; showCourse = true })
        }
        if (showCourse) CourseEditor(data, data.courses.find { it.id == courseId }, model) { showCourse = false }
        if (showTask) TaskEditor(data, data.tasks.find { it.id == taskId }, model) { showTask = false }
        if (settings) SettingsEditor(data, model) { settings = false }
    }
    model.message?.let { message ->
        AlertDialog(onDismissRequest = model::clearMessage, title = { Text("提示") }, text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) { Text(message) }
        },
            confirmButton = { TextButton(model::clearMessage) { Text("知道了") } })
    }
}
