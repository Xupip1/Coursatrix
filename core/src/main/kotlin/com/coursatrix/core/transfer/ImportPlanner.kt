package com.coursatrix.core.transfer

import com.coursatrix.core.model.AppData
import com.coursatrix.core.model.newId
import com.coursatrix.core.validation.Validation

object ImportPlanner {
    fun replaceTimetable(current: AppData, incoming: AppData): AppData {
        val ids = incoming.courses.map { it.id }.toSet()
        return incoming.copy(
            tasks = current.tasks.map { if (it.courseId in ids) it else it.copy(courseId = null) },
            preferences = current.preferences,
        ).also(Validation::requireValid)
    }

    fun mergeCsv(current: AppData, incoming: AppData): AppData {
        require(current.semester == incoming.semester && current.periods == incoming.periods) {
            "预览后学期或作息已改变，请重新选择 CSV 文件"
        }
        val courses = current.courses.toMutableList()
        val mapping = incoming.courses.associate { imported ->
            val existing = courses.firstOrNull { it.name == imported.name && it.teacher == imported.teacher }
            val chosen = existing ?: imported.copy(id = newId()).also { courses += it }
            imported.id to chosen.id
        }
        val meetings = current.meetings.toMutableList()
        val signatures = meetings.map { it.signature() }.toMutableSet()
        incoming.meetings.forEach { imported ->
            val meeting = imported.copy(id = newId(), courseId = mapping.getValue(imported.courseId))
            if (signatures.add(meeting.signature())) meetings += meeting
        }
        return current.copy(courses = courses, meetings = meetings).also(Validation::requireValid)
    }
}
