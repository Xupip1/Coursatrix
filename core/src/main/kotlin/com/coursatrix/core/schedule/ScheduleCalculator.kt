package com.coursatrix.core.schedule

import com.coursatrix.core.model.AppData
import com.coursatrix.core.model.Course
import com.coursatrix.core.model.Meeting
import com.coursatrix.core.model.Semester
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

object ScheduleCalculator {
    fun weekOn(semester: Semester, date: LocalDate): Int =
        Math.floorDiv(ChronoUnit.DAYS.between(semester.startDate, date), 7).toInt() + 1

    /** The Monday of week 1 such that [date] falls in teaching week [week]. */
    fun firstMondayFor(date: LocalDate, week: Int): LocalDate =
        date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(week - 1L)

    fun onDate(data: AppData, date: LocalDate): List<Session> {
        val week = weekOn(data.semester, date)
        if (week !in 1..data.semester.weekCount) return emptyList()
        return sessions(data, date) { week in it.weeks }
    }

    /**
     * Meetings on [date]'s weekday that do not take place in that week, excluding any that would overlap a
     * class actually held that day. Used to show the rest of the timetable greyed out.
     */
    fun inactiveOnDate(data: AppData, date: LocalDate): List<Session> {
        val week = weekOn(data.semester, date)
        if (week !in 1..data.semester.weekCount) return emptyList()
        val active = onDate(data, date)
        return sessions(data, date) { week !in it.weeks }.filter { candidate ->
            active.none { candidate.meeting.startPeriod <= it.meeting.endPeriod && it.meeting.startPeriod <= candidate.meeting.endPeriod }
        }
    }

    /** First class start (grouped by start time) whose reminder time lies strictly after [now], within the semester. */
    fun nextReminder(data: AppData, now: LocalDateTime, minutesBefore: Int): Reminder? {
        val lastDay = data.semester.startDate.plusWeeks(data.semester.weekCount.toLong()).minusDays(1)
        var date = maxOf(now.toLocalDate(), data.semester.startDate)
        while (!date.isAfter(lastDay)) {
            onDate(data, date).groupBy { it.start }.entries.sortedBy { it.key }.forEach { (start, sessions) ->
                val at = date.atTime(start).minusMinutes(minutesBefore.toLong())
                if (at.isAfter(now)) return Reminder(at, sessions)
            }
            date = date.plusDays(1)
        }
        return null
    }

    private fun sessions(data: AppData, date: LocalDate, include: (Meeting) -> Boolean): List<Session> {
        val courses = data.courses.associateBy { it.id }
        return data.meetings.filter { it.weekday == date.dayOfWeek.value && include(it) }
            .mapNotNull { meeting ->
                val course = courses[meeting.courseId] ?: return@mapNotNull null
                val start = data.periods.getOrNull(meeting.startPeriod - 1) ?: return@mapNotNull null
                val end = data.periods.getOrNull(meeting.endPeriod - 1) ?: return@mapNotNull null
                Session(course, meeting, date, start.startTime, end.endTime)
            }.sortedWith(compareBy({ it.start }, { it.course.name }))
    }

    fun conflicts(meetings: List<Meeting>, limit: Int = 100): List<Conflict> {
        val conflicts = mutableListOf<Conflict>()
        meetings.groupBy { it.weekday }.values.forEach { day ->
            val sorted = day.sortedBy { it.startPeriod }
            sorted.forEachIndexed { index, a ->
                for (b in sorted.drop(index + 1)) {
                    if (b.startPeriod > a.endPeriod) break
                    val weeks = a.weeks.intersect(b.weeks.toSet()).sorted()
                    if (weeks.isNotEmpty()) {
                        conflicts += Conflict(a, b, weeks)
                        if (conflicts.size >= limit) return conflicts
                    }
                }
            }
        }
        return conflicts
    }

    fun blocks(sessions: List<Session>): List<SessionBlock> {
        val blocks = mutableListOf<SessionBlock>()
        sessions.sortedBy { it.meeting.startPeriod }.forEach { session ->
            val last = blocks.lastOrNull()
            if (last != null && session.meeting.startPeriod <= last.endPeriod) {
                blocks[blocks.lastIndex] = last.copy(
                    endPeriod = maxOf(last.endPeriod, session.meeting.endPeriod),
                    sessions = last.sessions + session,
                )
            } else {
                blocks += SessionBlock(session.meeting.startPeriod, session.meeting.endPeriod, listOf(session))
            }
        }
        return blocks
    }
}

data class Session(val course: Course, val meeting: Meeting, val date: LocalDate, val start: LocalTime, val end: LocalTime)
data class SessionBlock(val startPeriod: Int, val endPeriod: Int, val sessions: List<Session>)
data class Reminder(val at: LocalDateTime, val sessions: List<Session>)
data class Conflict(val first: Meeting, val second: Meeting, val weeks: List<Int>)
