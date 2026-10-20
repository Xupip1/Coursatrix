package com.coursatrix.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.coursatrix.app.data.CoursatrixRepository
import com.coursatrix.core.model.*
import com.coursatrix.core.schedule.ScheduleCalculator
import com.coursatrix.core.transfer.*
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [school] is set for a school Excel timetable, which is applied like a shared timetable ([ContentKind.TIMETABLE]).
 * [source] names a reviewed PDF/screenshot import ("PDF", "截图"); with kind null it merges/replaces like CSV.
 */
data class ImportPreview(val data: AppData, val kind: ContentKind?, val errors: List<String> = emptyList(), val duplicates: Int = 0,
                         val school: SchoolXlsResult? = null, val source: String? = null)

class CoursatrixViewModel(private val repository: CoursatrixRepository) : ViewModel() {
    var message by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    val data = repository.data.catch { notify("无法读取本地数据：${it.message}") }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init { action { repository.initialize() } }

    fun notify(value: String) { message = value }
    fun clearMessage() { message = null }
    fun action(onSuccess: () -> Unit = {}, block: suspend () -> Unit) {
        if (busy) return
        viewModelScope.launch {
            busy = true
            try { block(); onSuccess() }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { notify(e.message ?: "操作失败，请重试") }
            finally { busy = false }
        }
    }

    fun saveCourse(course: Course, meetings: List<Meeting>, done: () -> Unit) =
        action(done) { repository.saveCourse(course, meetings) }
    fun deleteCourse(id: String, done: () -> Unit) = action(done) { repository.deleteCourse(id) }
    fun saveTask(task: PlanTask, done: () -> Unit) = action(done) { repository.saveTask(task) }
    fun deleteTask(id: String, done: () -> Unit) = action(done) { repository.deleteTask(id) }
    fun toggleTask(id: String) = action { repository.toggleTask(id) }
    fun saveSettings(semester: Semester, periods: List<Period>, preferences: Preferences, done: () -> Unit) =
        action(done) { repository.saveSettings(semester, periods, preferences) }
    fun setPreferences(preferences: Preferences) = action { repository.savePreferences(preferences) }
    fun setCurrentWeek(today: LocalDate, week: Int, done: () -> Unit) = action(done) {
        val monday = ScheduleCalculator.firstMondayFor(today, week)
        repository.setFirstMonday(monday.toString())
        notify("已设置：今天是第 $week 周，第一周周一为 $monday")
    }

    fun applyImport(preview: ImportPreview, merge: Boolean, done: () -> Unit) = action(done) {
        require(preview.errors.isEmpty()) { "请先修正导入错误" }
        when (preview.kind) {
            ContentKind.BACKUP -> repository.restore(preview.data)
            ContentKind.TIMETABLE -> repository.replaceTimetable(preview.data, false)
            null -> if (merge) repository.mergeCsv(preview.data) else repository.replaceTimetable(preview.data, true)
        }
        notify("导入完成")
    }

    companion object {
        fun factory(repository: CoursatrixRepository): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(CoursatrixViewModel::class.java))
                @Suppress("UNCHECKED_CAST")
                return CoursatrixViewModel(repository) as T
            }
        }
    }
}
