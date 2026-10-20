package com.coursatrix.app

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import com.coursatrix.app.data.CoursatrixRepository
import com.coursatrix.app.data.local.CoursatrixDatabase
import com.coursatrix.app.data.local.CourseRow
import com.coursatrix.core.model.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class RepositoryTest {
    private lateinit var database: CoursatrixDatabase
    private lateinit var repository: CoursatrixRepository
    private lateinit var sample: AppData

    @Before fun setup() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,
            CoursatrixDatabase::class.java).build()
        repository = CoursatrixRepository(database)
        repository.initialize()
        val initial = database.dao().read()!!.toModel()
        val course = Course(id = "test-course", semesterId = initial.semester.id, name = "示例课程")
        sample = initial.copy(courses = listOf(course), meetings = listOf(Meeting(id = "test-meeting", courseId = course.id,
            weekday = 1, startPeriod = 1, endPeriod = 2, weeks = listOf(1))),
            tasks = listOf(PlanTask(id = "test-task", title = "保留任务", courseId = course.id)))
        repository.restore(sample)
    }

    @After fun close() { database.close() }

    @Test fun failedDatabaseTransactionRestoresDeletedTasks() = runBlocking {
        val result = runCatching {
            database.withTransaction {
                database.dao().clearTasks()
                database.dao().putCourses(listOf(CourseRow("test-course", sample.semester.id, "重复主键", "", "#6960CE", "")))
            }
        }
        assertTrue(result.isFailure)
        assertEquals("保留任务", database.dao().read()!!.toModel().tasks.single().title)
    }

    @Test fun deletingCourseKeepsTaskAndUnlinksReference() = runBlocking {
        repository.deleteCourse("test-course")
        val restored = database.dao().read()!!.toModel()
        assertTrue(restored.courses.isEmpty())
        assertTrue(restored.meetings.isEmpty())
        assertNull(restored.tasks.single().courseId)
    }

    @Test fun invalidRestoreDoesNotChangeExistingData() = runBlocking {
        assertTrue(runCatching { repository.restore(sample.copy(periods = sample.periods.take(1))) }.isFailure)
        assertEquals(12, database.dao().read()!!.toModel().periods.size)
        assertEquals("test-course", database.dao().read()!!.toModel().courses.single().id)
    }
}
