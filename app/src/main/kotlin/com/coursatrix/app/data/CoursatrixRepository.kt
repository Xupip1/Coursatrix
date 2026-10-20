package com.coursatrix.app.data

import androidx.room.withTransaction
import com.coursatrix.app.data.local.CoursatrixDatabase
import com.coursatrix.core.model.*
import com.coursatrix.core.transfer.ImportPlanner
import com.coursatrix.core.validation.Validation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

class CoursatrixRepository(private val database: CoursatrixDatabase) {
    private val dao = database.dao()
    val data = dao.observe().map { it?.toModel() }.flowOn(Dispatchers.IO)

    /** One-off read for background work (reminders, widget) outside the UI flow. */
    suspend fun snapshot(): AppData? = dao.read()?.toModel()

    suspend fun initialize() = database.withTransaction {
        if (dao.read() == null) dao.write(AppData())
    }

    private suspend fun change(transform: (AppData) -> AppData) = database.withTransaction {
        val current = requireNotNull(dao.read()) { "数据库尚未准备好" }.toModel()
        val updated = transform(current)
        Validation.requireValid(updated)
        dao.write(updated)
    }

    suspend fun saveCourse(course: Course, meetings: List<Meeting>) = change { current ->
        current.copy(courses = current.courses.filterNot { it.id == course.id } + course,
            meetings = current.meetings.filterNot { it.courseId == course.id } + meetings)
    }

    suspend fun deleteCourse(id: String) = change { current ->
        current.copy(courses = current.courses.filterNot { it.id == id },
            meetings = current.meetings.filterNot { it.courseId == id },
            tasks = current.tasks.map { if (it.courseId == id) it.copy(courseId = null) else it })
    }

    suspend fun saveTask(task: PlanTask) = change { it.copy(tasks = it.tasks.filterNot { t -> t.id == task.id } + task) }
    suspend fun deleteTask(id: String) = change { it.copy(tasks = it.tasks.filterNot { t -> t.id == id }) }
    suspend fun toggleTask(id: String) = change { it.copy(tasks = it.tasks.map { t -> if (t.id == id) t.copy(completed = !t.completed) else t }) }
    suspend fun saveSettings(semester: Semester, periods: List<Period>, preferences: Preferences) = change {
        it.copy(semester = semester, periods = periods, preferences = preferences)
    }
    suspend fun setFirstMonday(value: String) = change { it.copy(semester = it.semester.copy(firstMonday = value)) }
    suspend fun savePreferences(preferences: Preferences) = change { it.copy(preferences = preferences) }
    suspend fun restore(data: AppData) = change { data }
    suspend fun replaceTimetable(data: AppData, csv: Boolean) = change {
        if (csv) require(it.semester == data.semester && it.periods == data.periods) { "学期或作息已改变，请重新导入 CSV" }
        ImportPlanner.replaceTimetable(it, data)
    }
    suspend fun mergeCsv(data: AppData) = change { ImportPlanner.mergeCsv(it, data) }
}
