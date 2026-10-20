package com.coursatrix.app.ui.profile

import android.content.Context
import android.net.Uri
import com.coursatrix.core.validation.Limits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

private const val MAX_DOCUMENT_BYTES = 20 * 1024 * 1024

suspend fun readDocument(context: Context, uri: Uri): ByteArray = withContext(Dispatchers.IO) {
    requireNotNull(context.contentResolver.openInputStream(uri)) { "无法打开文件" }.use { stream ->
        val buffer = ByteArray(16 * 1024)
        val output = ByteArrayOutputStream()
        var count = stream.read(buffer)
        while (count != -1) {
            // Screenshots and PDFs may be larger; text and Excel formats still enforce their own 4 MiB limit.
            require(output.size() + count <= MAX_DOCUMENT_BYTES) { "文件超过 20 MiB，请压缩图片或精简文件" }
            output.write(buffer, 0, count)
            count = stream.read(buffer)
        }
        output.toByteArray()
    }
}

fun decodeText(bytes: ByteArray): String {
    return try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    } catch (e: java.nio.charset.CharacterCodingException) {
        throw IllegalArgumentException("文件不是 UTF-8 文本。请将表格另存为「CSV UTF-8」，图片和 PDF 暂不支持直接识别。", e)
    }
}

suspend fun writeDocument(context: Context, uri: Uri, text: String) = withContext(Dispatchers.IO) {
    requireNotNull(context.contentResolver.openOutputStream(uri, "wt")) { "无法写入该位置" }.use {
        it.write(text.toByteArray(Charsets.UTF_8))
    }
}
