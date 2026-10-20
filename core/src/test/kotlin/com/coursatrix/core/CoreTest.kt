package com.coursatrix.core

import com.coursatrix.core.model.*
import com.coursatrix.core.schedule.ScheduleCalculator
import com.coursatrix.core.transfer.*
import com.coursatrix.core.validation.Validation
import com.coursatrix.core.validation.WeekPattern
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class CoreTest {
    private fun fixture(): AppData {
        val semester = Semester(id = "term", firstMonday = "2026-09-07", weekCount = 20)
        val course = Course(id = "course", semesterId = semester.id, name = "大学数学", teacher = "示例教师")
        return AppData(semester = semester, courses = listOf(course), meetings = listOf(
            Meeting(id = "m1", courseId = course.id, weekday = 1, startPeriod = 1, endPeriod = 2,
                weeks = (1..16).toList(), location = "教学楼 A101"),
            Meeting(id = "m2", courseId = course.id, weekday = 4, startPeriod = 10, endPeriod = 11,
                weeks = listOf(1, 3, 5), location = "教学楼 B202"),
        ))
    }

    @Test fun weeksBeforeStartUseFloorDivision() {
        val data = fixture()
        assertEquals(0, ScheduleCalculator.weekOn(data.semester, LocalDate.parse("2026-09-06")))
        assertEquals(-1, ScheduleCalculator.weekOn(data.semester, LocalDate.parse("2026-08-30")))
        assertTrue(ScheduleCalculator.onDate(data, LocalDate.parse("2026-09-06")).isEmpty())
    }

    @Test fun oneCourseHasDifferentRoomsAndEveningSessions() {
        val data = fixture()
        assertEquals("教学楼 A101", ScheduleCalculator.onDate(data, LocalDate.parse("2026-09-07")).single().meeting.location)
        val evening = ScheduleCalculator.onDate(data, LocalDate.parse("2026-09-10")).single()
        assertEquals("19:00", evening.start.toString())
        assertEquals("教学楼 B202", evening.meeting.location)
        assertTrue(ScheduleCalculator.onDate(data, LocalDate.parse("2026-09-17")).isEmpty())
    }

    @Test fun weekRulesAndRoundTrip() {
        assertEquals(listOf(1, 3, 5), WeekPattern.parse("1-6单", 20))
        assertEquals(listOf(10, 12), WeekPattern.parse("10-12(双)周", 20))
        val weeks = listOf(1, 2, 3, 7, 9, 10)
        assertEquals(weeks, WeekPattern.parse(WeekPattern.format(weeks), 20))
        assertThrows(IllegalArgumentException::class.java) { WeekPattern.parse("1-99999999", 20) }
        assertThrows(IllegalArgumentException::class.java) { WeekPattern.parse("2-1", 20) }
    }

    @Test fun settingCurrentWeekDerivesFirstMonday() {
        val wednesday = LocalDate.parse("2026-10-21")
        assertEquals(LocalDate.parse("2026-09-07"), ScheduleCalculator.firstMondayFor(wednesday, 7))
        assertEquals(LocalDate.parse("2026-10-19"), ScheduleCalculator.firstMondayFor(wednesday, 1))
        val semester = fixture().semester.copy(firstMonday = ScheduleCalculator.firstMondayFor(wednesday, 7).toString())
        assertEquals(7, ScheduleCalculator.weekOn(semester, wednesday))
    }

    @Test fun inactiveCoursesFillOnlyFreeSlots() {
        val data = fixture().let { d ->
            val other = Course(id = "other", semesterId = d.semester.id, name = "单周课")
            d.copy(courses = d.courses + other, meetings = d.meetings + listOf(
                Meeting(id = "m3", courseId = other.id, weekday = 4, startPeriod = 1, endPeriod = 2, weeks = listOf(1)),
                Meeting(id = "m4", courseId = other.id, weekday = 4, startPeriod = 11, endPeriod = 12, weeks = listOf(2)),
            ))
        }
        val thursdayWeek2 = LocalDate.parse("2026-09-17")
        assertEquals(listOf("m4"), ScheduleCalculator.onDate(data, thursdayWeek2).map { it.meeting.id })
        // m2 (periods 10–11, odd weeks) overlaps m4 held this week, so only m3 is shown greyed out.
        assertEquals(listOf("m3"), ScheduleCalculator.inactiveOnDate(data, thursdayWeek2).map { it.meeting.id })
        assertTrue(ScheduleCalculator.inactiveOnDate(data, LocalDate.parse("2026-09-06")).isEmpty())
    }

    @Test fun nextReminderSkipsPastAndGroupsSameStart() {
        val data = fixture()
        val first = ScheduleCalculator.nextReminder(data, java.time.LocalDateTime.parse("2026-09-07T07:00"), 10)!!
        assertEquals("2026-09-07T07:50", first.at.toString())
        assertEquals("m1", first.sessions.single().meeting.id)
        val next = ScheduleCalculator.nextReminder(data, java.time.LocalDateTime.parse("2026-09-07T07:50"), 10)!!
        assertEquals("2026-09-10T18:50", next.at.toString())
        assertNull(ScheduleCalculator.nextReminder(data, java.time.LocalDateTime.parse("2027-06-01T00:00"), 10))
    }

    @Test fun conflictsRequireIntersectingWeeks() {
        val a = fixture().meetings.first().copy(weeks = listOf(1, 3))
        val b = a.copy(id = "other", weeks = listOf(2, 4))
        assertTrue(ScheduleCalculator.conflicts(listOf(a, b)).isEmpty())
        assertEquals(listOf(3), ScheduleCalculator.conflicts(listOf(a, b.copy(weeks = listOf(3, 4)))).single().weeks)
    }

    @Test fun adjacentSessionsAreNotConflicting() {
        val a = fixture().meetings.first()
        assertTrue(ScheduleCalculator.conflicts(listOf(a, a.copy(id = "b", startPeriod = 3, endPeriod = 4))).isEmpty())
    }

    @Test fun rejectInvalidTimesMissingReferencesAndShrinkingSettings() {
        val data = fixture()
        assertTrue(Validation.errors(data).isEmpty())
        assertTrue(Validation.errors(data.copy(periods = data.periods.take(6))).isNotEmpty())
        assertTrue(Validation.errors(data.copy(semester = data.semester.copy(weekCount = 2))).isNotEmpty())
        assertTrue(Validation.errors(data.copy(periods = listOf(Period(1, "12:00", "11:00")))).isNotEmpty())
        assertTrue(Validation.errors(data.copy(tasks = listOf(PlanTask(title = "作业", courseId = "missing")))).isNotEmpty())
    }

    @Test fun backupRoundTripAndPrivateShare() {
        val data = fixture().copy(tasks = listOf(PlanTask(title = "私人计划")))
        assertEquals(data, BackupCodec.decode(BackupCodec.encode(data)).data)
        val shared = BackupCodec.decode(BackupCodec.encode(data, ContentKind.TIMETABLE, true, true))
        assertTrue(shared.data.tasks.isEmpty())
        assertTrue(shared.data.courses.all { it.teacher.isEmpty() && it.note.isEmpty() })
        assertTrue(shared.data.meetings.all { it.location.isEmpty() })
    }

    @Test fun rejectsUnknownBackupVersionAndBrokenReference() {
        val json = BackupCodec.encode(fixture())
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(json.replace("\"schemaVersion\": 1", "\"schemaVersion\": 99")) }
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(json.replace("\"courseId\": \"course\"", "\"courseId\": \"missing\"")) }
    }

    @Test fun csvHandlesChineseCommasQuotesNewlinesAndBom() {
        val data = fixture().let { it.copy(courses = it.courses.map { c -> c.copy(name = "数学,进阶\"A\"", note = "第一行\n第二行") }) }
        val parsed = CsvCodec.decode(CsvCodec.encode(data), data)
        assertTrue(parsed.errors.toString(), parsed.errors.isEmpty())
        assertEquals(data.courses.single().name, parsed.data.courses.single().name)
        assertEquals(data.courses.single().note, parsed.data.courses.single().note)
        assertEquals(2, parsed.data.meetings.size)
    }

    @Test fun csvProtectsFormulaAndLiteralApostrophe() {
        for (name in listOf("=1+1", "+cmd", "@value", "'原始名称", "'=literal", "\t=1")) {
            val data = fixture().let { it.copy(courses = it.courses.map { c -> c.copy(name = name) }) }
            val csv = CsvCodec.encode(data)
            assertTrue(csv.contains("\"'"))
            assertEquals(name.trim(), CsvCodec.decode(csv, data).data.courses.single().name)
        }
    }

    @Test fun csvBadRowHasLineNumberAndUnclosedQuotesFail() {
        val data = fixture()
        val csv = CsvCodec.headers.joinToString(",") + "\n错误课程,教师,教室,8,1,2,1-16,#6960CE,"
        val parsed = CsvCodec.decode(csv, data)
        assertTrue(parsed.errors.first().contains("第 2 行"))
        assertThrows(IllegalArgumentException::class.java) { CsvCodec.decode(csv + "\n\"未闭合", data) }
    }

    @Test fun jsonDepthGuardIgnoresBracesInsideEscapedStrings() {
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode("[".repeat(33) + "]".repeat(33)) }
        val data = fixture().let { it.copy(courses = it.courses.map { c -> c.copy(name = "{".repeat(80) + "\\\"") }) }
        assertEquals(data, BackupCodec.decode(BackupCodec.encode(data)).data)
    }

    @Test fun oversizedInputAndNonMinuteTimeAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { CsvCodec.decode("a".repeat(4 * 1024 * 1024 + 1), fixture()) }
        val data = fixture()
        assertTrue(Validation.errors(data.copy(periods = data.periods.mapIndexed { i, p ->
            if (i == 0) p.copy(start = "08:00:01") else p
        })).isNotEmpty())
    }

    @Test fun csvPreviewCannotMergeAfterSettingsChange() {
        val data = fixture()
        val incoming = CsvCodec.decode(CsvCodec.encode(data), data).data
        assertThrows(IllegalArgumentException::class.java) {
            ImportPlanner.mergeCsv(data.copy(semester = data.semester.copy(name = "另一个学期")), incoming)
        }
    }

    @Test fun mergingTwiceDeduplicatesAndReplacementPreservesTasks() {
        val current = fixture().copy(tasks = listOf(PlanTask(title = "保留此任务", courseId = "course")))
        val incoming = CsvCodec.decode(CsvCodec.encode(current), current).data
        val merged = ImportPlanner.mergeCsv(current, incoming)
        assertEquals(current.meetings.size, merged.meetings.size)
        assertEquals(merged.meetings.size, ImportPlanner.mergeCsv(merged, incoming).meetings.size)
        val replaced = ImportPlanner.replaceTimetable(current, incoming)
        assertEquals("保留此任务", replaced.tasks.single().title)
        assertNull(replaced.tasks.single().courseId)
    }
}
