package com.coursatrix.app.data.local

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.coursatrix.core.model.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "semester")
data class SemesterRow(
    @PrimaryKey val id: Int = 1,
    val key: String, val school: String, val name: String, val firstMonday: String, val weekCount: Int,
    val theme: String, val showWeekend: Boolean,
    @ColumnInfo(defaultValue = "1") val showInactive: Boolean = true,
    @ColumnInfo(defaultValue = "VIOLET") val accent: String = AccentColor.VIOLET.name,
    @ColumnInfo(defaultValue = "SOFT") val cardStyle: String = CardStyle.SOFT.name,
    @ColumnInfo(defaultValue = "1") val showLocation: Boolean = true,
    @ColumnInfo(defaultValue = "0") val showTeacher: Boolean = false,
    @ColumnInfo(defaultValue = "1") val showPeriodTimes: Boolean = true,
    @ColumnInfo(defaultValue = "1") val showGridLines: Boolean = true,
)

@Entity(tableName = "periods")
data class PeriodRow(@PrimaryKey val number: Int, val start: String, val end: String, val ownerId: Int = 1)

@Entity(tableName = "courses")
data class CourseRow(
    @PrimaryKey val id: String, val semesterId: String, val name: String, val teacher: String,
    val color: String, val note: String, val ownerId: Int = 1,
)

@Entity(tableName = "meetings", foreignKeys = [ForeignKey(
    entity = CourseRow::class, parentColumns = ["id"], childColumns = ["courseId"], onDelete = ForeignKey.CASCADE,
)], indices = [Index("courseId")])
data class MeetingRow(
    @PrimaryKey val id: String, val courseId: String, val weekday: Int, val startPeriod: Int,
    val endPeriod: Int, val weeks: String, val location: String, val ownerId: Int = 1,
)

@Entity(tableName = "tasks", foreignKeys = [ForeignKey(
    entity = CourseRow::class, parentColumns = ["id"], childColumns = ["courseId"], onDelete = ForeignKey.SET_NULL,
)], indices = [Index("courseId")])
data class TaskRow(
    @PrimaryKey val id: String, val title: String, val kind: String, val dueDate: String?,
    val completed: Boolean, val courseId: String?, val note: String, val ownerId: Int = 1,
)

data class StoredState(
    @Embedded val semester: SemesterRow,
    @Relation(parentColumn = "id", entityColumn = "ownerId") val periods: List<PeriodRow>,
    @Relation(parentColumn = "id", entityColumn = "ownerId") val courses: List<CourseRow>,
    @Relation(parentColumn = "id", entityColumn = "ownerId") val meetings: List<MeetingRow>,
    @Relation(parentColumn = "id", entityColumn = "ownerId") val tasks: List<TaskRow>,
) {
    fun toModel() = AppData(
        semester = Semester(semester.key, semester.school, semester.name, semester.firstMonday, semester.weekCount),
        periods = periods.sortedBy { it.number }.map { Period(it.number, it.start, it.end) },
        courses = courses.sortedBy { it.name }.map { Course(it.id, it.semesterId, it.name, it.teacher, it.color, it.note) },
        meetings = meetings.sortedBy { it.id }.map { Meeting(it.id, it.courseId, it.weekday, it.startPeriod,
            it.endPeriod, it.weeks.split(',').map(String::toInt), it.location) },
        tasks = tasks.sortedBy { it.id }.map { PlanTask(it.id, it.title, TaskKind.valueOf(it.kind),
            it.dueDate, it.completed, it.courseId, it.note) },
        preferences = Preferences(ThemeMode.valueOf(semester.theme), semester.showWeekend, semester.showInactive,
            enumOr(semester.accent, AccentColor.VIOLET), enumOr(semester.cardStyle, CardStyle.SOFT), semester.showLocation,
            semester.showTeacher, semester.showPeriodTimes, semester.showGridLines),
    )
}

@Dao
interface CoursatrixDao {
    @Transaction @Query("SELECT * FROM semester WHERE id = 1") fun observe(): Flow<StoredState?>
    @Transaction @Query("SELECT * FROM semester WHERE id = 1") suspend fun read(): StoredState?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putSemester(value: SemesterRow)
    @Insert suspend fun putPeriods(values: List<PeriodRow>)
    @Insert suspend fun putCourses(values: List<CourseRow>)
    @Insert suspend fun putMeetings(values: List<MeetingRow>)
    @Insert suspend fun putTasks(values: List<TaskRow>)
    @Query("DELETE FROM tasks") suspend fun clearTasks()
    @Query("DELETE FROM meetings") suspend fun clearMeetings()
    @Query("DELETE FROM courses") suspend fun clearCourses()
    @Query("DELETE FROM periods") suspend fun clearPeriods()

    suspend fun write(data: AppData) {
        clearTasks()
        clearMeetings()
        clearCourses()
        clearPeriods()
        val s = data.semester
        putSemester(SemesterRow(key = s.id, school = s.school, name = s.name, firstMonday = s.firstMonday,
            weekCount = s.weekCount, theme = data.preferences.theme.name, showWeekend = data.preferences.showWeekend,
            showInactive = data.preferences.showInactive, accent = data.preferences.accent.name,
            cardStyle = data.preferences.cardStyle.name, showLocation = data.preferences.showLocation,
            showTeacher = data.preferences.showTeacher, showPeriodTimes = data.preferences.showPeriodTimes,
            showGridLines = data.preferences.showGridLines))
        putPeriods(data.periods.map { PeriodRow(it.number, it.start, it.end) })
        putCourses(data.courses.map { CourseRow(it.id, it.semesterId, it.name, it.teacher, it.color, it.note) })
        putMeetings(data.meetings.map { MeetingRow(it.id, it.courseId, it.weekday, it.startPeriod,
            it.endPeriod, it.weeks.sorted().joinToString(","), it.location) })
        putTasks(data.tasks.map { TaskRow(it.id, it.title, it.kind.name, it.dueDate, it.completed, it.courseId, it.note) })
    }
}

@Database(entities = [SemesterRow::class, PeriodRow::class, CourseRow::class, MeetingRow::class, TaskRow::class],
    version = 3, exportSchema = true)
abstract class CoursatrixDatabase : RoomDatabase() {
    abstract fun dao(): CoursatrixDao
    companion object {
        fun open(context: Context): CoursatrixDatabase = Room.databaseBuilder(
            context.applicationContext, CoursatrixDatabase::class.java, "coursatrix.db",
        ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE semester ADD COLUMN showInactive INTEGER NOT NULL DEFAULT 1")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                listOf("accent TEXT NOT NULL DEFAULT 'VIOLET'", "cardStyle TEXT NOT NULL DEFAULT 'SOFT'",
                    "showLocation INTEGER NOT NULL DEFAULT 1", "showTeacher INTEGER NOT NULL DEFAULT 0",
                    "showPeriodTimes INTEGER NOT NULL DEFAULT 1", "showGridLines INTEGER NOT NULL DEFAULT 1",
                ).forEach { db.execSQL("ALTER TABLE semester ADD COLUMN $it") }
            }
        }
    }
}

/** Unknown stored values (e.g. from a newer version) fall back instead of crashing. */
private inline fun <reified T : Enum<T>> enumOr(name: String, fallback: T): T = runCatching { enumValueOf<T>(name) }.getOrDefault(fallback)
