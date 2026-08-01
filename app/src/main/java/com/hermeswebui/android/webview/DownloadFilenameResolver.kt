package com.hermeswebui.android.webview

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.Locale

internal object DownloadFilenameResolver {
    const val MAX_FILENAME_CHARS = 180

    private val unsafeFilenameCharacters = Regex("[\\p{Cc}/\\\\:*?\"<>|]")
    private val mimeExtensions = mapOf(
        "application/json" to "json",
        "application/pdf" to "pdf",
        "application/vnd.ms-excel" to "xls",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" to "xlsx",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" to "docx",
        "application/zip" to "zip",
        "image/gif" to "gif",
        "image/jpeg" to "jpg",
        "image/png" to "png",
        "image/webp" to "webp",
        "text/csv" to "csv",
        "text/html" to "html",
        "text/plain" to "txt"
    )

    fun resolve(
        requestedFilename: String? = null,
        contentDisposition: String? = null,
        url: String? = null,
        mimeType: String? = null,
        fallbackBaseName: String = "hermes-download"
    ): String {
        val candidate = sequenceOf(
            requestedFilename,
            filenameFromContentDisposition(contentDisposition),
            filenameFromUrl(url)
        ).mapNotNull(::sanitize)
            .firstOrNull()
        if (candidate != null) return candidate

        val base = sanitize(fallbackBaseName) ?: "hermes-download"
        val extension = extensionForMimeType(mimeType)
        return if (extension == null) base else truncate("$base.$extension")
    }

    fun sanitize(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val leaf = raw
            .replace('\\', '/')
            .substringAfterLast('/')
            .replace(unsafeFilenameCharacters, "_")
            .trim()
            .trimEnd('.')
        if (leaf.isBlank() || leaf == "." || leaf == "..") return null
        return truncate(leaf).takeIf { it.isNotBlank() }
    }

    fun normalizeMimeType(raw: String?): String? {
        if (raw.isNullOrBlank() || raw.any(Char::isISOControl)) return null
        val normalized = raw.substringBefore(';').trim().lowercase(Locale.ROOT)
        return normalized.takeIf {
            it.matches(Regex("^[a-z0-9!#$&^_.+-]{1,64}/[a-z0-9!#$&^_.+-]{1,64}$"))
        }
    }

    internal fun filenameFromContentDisposition(header: String?): String? {
        if (header.isNullOrBlank()) return null
        val extended = Regex(
            "(?:^|;)\\s*filename\\*\\s*=\\s*([^;]+)",
            RegexOption.IGNORE_CASE
        ).find(header)?.groupValues?.get(1)?.trim()?.trim('"')
        decodeExtendedFilename(extended)?.let { return it }

        return Regex(
            "(?:^|;)\\s*filename\\s*=\\s*(?:\"((?:\\\\.|[^\"])*)\"|([^;]*))",
            RegexOption.IGNORE_CASE
        ).find(header)?.let { match ->
            val quoted = match.groupValues[1]
            if (quoted.isNotEmpty()) {
                quoted.replace(Regex("\\\\(.)"), "$1")
            } else {
                match.groupValues[2].trim()
            }
        }
    }

    private fun decodeExtendedFilename(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val parts = value.split('\'', limit = 3)
        if (parts.size != 3) return null
        val charset = when (parts[0].uppercase(Locale.ROOT)) {
            "UTF-8" -> StandardCharsets.UTF_8
            "ISO-8859-1" -> StandardCharsets.ISO_8859_1
            "US-ASCII" -> StandardCharsets.US_ASCII
            else -> return null
        }
        return decodePercentEncoded(parts[2], charset)
    }

    private fun filenameFromUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val rawLeaf = runCatching { URI(url).rawPath }
            .getOrNull()
            ?.substringAfterLast('/')
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return decodePercentEncoded(rawLeaf, StandardCharsets.UTF_8)
    }

    private fun decodePercentEncoded(value: String, charset: Charset): String? {
        return runCatching {
            URLDecoder.decode(value.replace("+", "%2B"), charset.name())
        }.getOrNull()
    }

    private fun extensionForMimeType(mimeType: String?): String? {
        return mimeExtensions[normalizeMimeType(mimeType)]
    }

    private fun truncate(value: String): String = value.take(MAX_FILENAME_CHARS)
}
