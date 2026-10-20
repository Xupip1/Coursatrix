package com.coursatrix.app

import android.app.Application
import com.coursatrix.app.data.CoursatrixRepository
import com.coursatrix.app.data.local.CoursatrixDatabase
import com.coursatrix.app.reminder.Reminders
import com.coursatrix.app.widget.TodayWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

class CoursatrixApplication : Application() {
    val repository by lazy { CoursatrixRepository(CoursatrixDatabase.open(this)) }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        Reminders.createChannel(this)
        // Any change to the timetable reschedules the next reminder and refreshes home-screen widgets.
        scope.launch {
            repository.data.distinctUntilChanged().catch { }.collect { data ->
                Reminders.schedule(this@CoursatrixApplication, data)
                TodayWidget.update(this@CoursatrixApplication, data)
            }
        }
    }
}
