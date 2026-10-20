package com.coursatrix.core.transfer

import com.coursatrix.core.model.AppData
import com.coursatrix.core.model.Preferences
import com.coursatrix.core.validation.Limits
import com.coursatrix.core.validation.Validation
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant

@Serializable
enum class ContentKind { BACKUP, TIMETABLE }

@Serializable
data class BackupEnvelope(
    val format: String,
    val schemaVersion: Int,
    val kind: ContentKind,
    val exportedAt: String,
    val data: AppData,
)

object BackupCodec {
    private val json = Json { prettyPrint = true; encodeDefaults = true }

    private fun checkDepth(text: String) {
        var depth = 0
        var inString = false
        var escaped = false
        text.forEach { char ->
            if (inString) {
                if (escaped) escaped = false
                else if (char == '\\') escaped = true
                else if (char == '"') inString = false
            } else when (char) {
                '"' -> inString = true
                '{', '[' -> { depth++; require(depth <= 32) { "JSON 嵌套超过 32 层，文件可能已损坏" } }
                '}', ']' -> depth--
            }
        }
    }

    fun encode(data: AppData, kind: ContentKind = ContentKind.BACKUP,
               hideTeachers: Boolean = false, hideLocations: Boolean = false): String {
        Validation.requireValid(data)
        val content = if (kind == ContentKind.BACKUP) data else data.copy(
            courses = data.courses.map { it.copy(teacher = if (hideTeachers) "" else it.teacher, note = "") },
            meetings = data.meetings.map { it.copy(location = if (hideLocations) "" else it.location) },
            tasks = emptyList(), preferences = Preferences(),
        )
        return json.encodeToString(BackupEnvelope("coursatrix", 1, kind, Instant.now().toString(), content)).also {
            require(it.toByteArray(Charsets.UTF_8).size <= Limits.FILE_BYTES) { "导出文件超过 4 MiB，请减少数据后重试" }
        }
    }

    fun decode(text: String): BackupEnvelope {
        require(text.toByteArray(Charsets.UTF_8).size <= Limits.FILE_BYTES) { "文件超过 4 MiB" }
        checkDepth(text)
        val envelope = try {
            json.decodeFromString<BackupEnvelope>(text.removePrefix(0xFEFF.toChar().toString()))
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("不是有效的 Coursatrix 备份文件，或其结构/版本不受支持", e)
        }
        require(envelope.format == "coursatrix") { "不是 Coursatrix 文件" }
        require(envelope.schemaVersion == 1) { "不支持此文件版本：${envelope.schemaVersion}" }
        require(runCatching { Instant.parse(envelope.exportedAt) }.isSuccess) { "导出时间无效" }
        Validation.requireValid(envelope.data)
        if (envelope.kind == ContentKind.TIMETABLE) {
            require(envelope.data.tasks.isEmpty() && envelope.data.courses.all { it.note.isEmpty() }) {
                "分享课表不应包含计划或私人备注"
            }
        }
        return envelope
    }
}
