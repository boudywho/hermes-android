package com.hermeswebui.android.webview

import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.util.Locale

internal object BlobDownloadProtocol {
    const val BRIDGE_NAME = "HermesAndroidBlobDownloads"
    const val CHUNK_BYTES = 64 * 1024
    const val MAX_TOTAL_BYTES = 50L * 1024 * 1024
    const val MAX_MESSAGE_CHARS = 100_000
    const val MAX_ID_CHARS = 64
    const val MAX_FILENAME_CHARS = 180
    const val MAX_URL_CHARS = 4096
    const val MAX_MIME_CHARS = 120
    const val TRANSFER_TIMEOUT_MS = 30_000L

    private val idPattern = Regex("^[A-Za-z0-9_-]{1,$MAX_ID_CHARS}$")
    private val mimePattern =
        Regex("^[A-Za-z0-9!#$&^_.+-]{1,64}/[A-Za-z0-9!#$&^_.+-]{1,64}$")

    sealed interface Message {
        val id: String

        data class Start(
            override val id: String,
            val filename: String,
            val mime: String,
            val size: Long
        ) : Message

        data class Chunk(
            override val id: String,
            val sequence: Int,
            val data: String
        ) : Message

        data class Finish(override val id: String) : Message
        data class Abort(override val id: String) : Message
        data class Download(
            override val id: String,
            val url: String,
            val filename: String
        ) : Message
    }

    fun parse(raw: String?): Message? {
        if (raw.isNullOrEmpty() || raw.length > MAX_MESSAGE_CHARS) return null
        val json = try {
            val tokener = JSONTokener(raw)
            val value = tokener.nextValue()
            if (value !is JSONObject || tokener.nextClean() != 0.toChar()) return null
            value
        } catch (_: JSONException) {
            return null
        }
        val type = json.opt("type") as? String ?: return null
        val id = (json.opt("id") as? String)?.takeIf(idPattern::matches) ?: return null
        return when (type) {
            "start" -> parseStart(json, id)
            "chunk" -> parseChunk(json, id)
            "finish" -> Message.Finish(id).takeIf { json.length() == 2 }
            "abort" -> Message.Abort(id).takeIf { json.length() == 2 }
            "download" -> parseDownload(json, id)
            else -> null
        }
    }

    fun normalizeMime(value: String): String? {
        if (value.isEmpty() ||
            value.length > MAX_MIME_CHARS ||
            value.any(Char::isISOControl)
        ) {
            return null
        }
        return value
            .substringBefore(';')
            .trim()
            .lowercase(Locale.ROOT)
            .takeIf(mimePattern::matches)
    }

    fun maxEncodedChunkChars(): Int = ((CHUNK_BYTES + 2) / 3) * 4

    private fun parseStart(json: JSONObject, id: String): Message.Start? {
        if (json.length() != 5) return null
        val filename = json.opt("filename") as? String ?: return null
        val rawMime = json.opt("mime") as? String ?: return null
        val mime = normalizeMime(rawMime) ?: return null
        val sizeNumber = json.opt("size") as? Number ?: return null
        val size = sizeNumber.toLong()
        if (filename.isBlank() || filename.length > MAX_FILENAME_CHARS) return null
        if (sizeNumber.toDouble() != size.toDouble() || size !in 0..MAX_TOTAL_BYTES) return null
        return Message.Start(id, filename, mime, size)
    }

    private fun parseChunk(json: JSONObject, id: String): Message.Chunk? {
        if (json.length() != 4) return null
        val sequenceNumber = json.opt("sequence") as? Number ?: return null
        val sequence = sequenceNumber.toInt()
        val data = json.opt("data") as? String ?: return null
        if (sequenceNumber.toDouble() != sequence.toDouble() || sequence < 0) return null
        if (data.isEmpty() || data.length > maxEncodedChunkChars()) return null
        return Message.Chunk(id, sequence, data)
    }

    private fun parseDownload(json: JSONObject, id: String): Message.Download? {
        if (json.length() != 4) return null
        val url = json.opt("url") as? String ?: return null
        val filename = json.opt("filename") as? String ?: return null
        if (url.isBlank() || url.length > MAX_URL_CHARS || url.any(Char::isISOControl)) return null
        if (filename.isBlank() || filename.length > MAX_FILENAME_CHARS) return null
        return Message.Download(id, url, filename)
    }
}
