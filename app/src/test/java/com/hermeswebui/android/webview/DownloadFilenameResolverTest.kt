package com.hermeswebui.android.webview

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DownloadFilenameResolverTest {
    @Test
    fun `requested filename wins and valid extensionless names stay extensionless`() {
        assertThat(
            DownloadFilenameResolver.resolve(
                requestedFilename = "report",
                contentDisposition = "attachment; filename=server.csv",
                url = "https://hermes.example/fallback.json",
                mimeType = "application/octet-stream"
            )
        ).isEqualTo("report")
        assertThat(
            DownloadFilenameResolver.resolve(
                requestedFilename = "media",
                mimeType = "application/octet-stream"
            )
        ).isEqualTo("media")
        assertThat(
            DownloadFilenameResolver.resolve(
                requestedFilename = "report.csv",
                mimeType = "application/octet-stream"
            )
        ).isEqualTo("report.csv")
    }

    @Test
    fun `preserves unicode filenames and normal extensions`() {
        assertThat(
            DownloadFilenameResolver.resolve(
                requestedFilename = "月次レポート.csv",
                mimeType = "text/csv"
            )
        ).isEqualTo("月次レポート.csv")
    }

    @Test
    fun `decodes RFC 5987 filename before plain content disposition filename`() {
        assertThat(
            DownloadFilenameResolver.resolve(
                contentDisposition =
                    "attachment; filename=plain.csv; filename*=UTF-8''Q2%20r%C3%A9sum%C3%A9.csv",
                url = "https://hermes.example/download",
                mimeType = "text/csv"
            )
        ).isEqualTo("Q2 résumé.csv")
    }

    @Test
    fun `uses a decoded URL path filename when content disposition has no name`() {
        assertThat(
            DownloadFilenameResolver.resolve(
                url = "https://hermes.example/files/monthly%20report.csv?token=secret",
                mimeType = "text/csv"
            )
        ).isEqualTo("monthly report.csv")
    }

    @Test
    fun `removes traversal components and replaces unsafe filename characters`() {
        assertThat(
            DownloadFilenameResolver.resolve(
                requestedFilename = "../../private/report:Q2?.csv",
                mimeType = "text/csv"
            )
        ).isEqualTo("report_Q2_.csv")
        assertThat(DownloadFilenameResolver.sanitize("..\\..\\secret.txt"))
            .isEqualTo("secret.txt")
    }

    @Test
    fun `adds MIME extension only for a generated fallback`() {
        assertThat(
            DownloadFilenameResolver.resolve(
                url = "https://hermes.example/download/",
                mimeType = "text/csv"
            )
        ).isEqualTo("hermes-download.csv")
        assertThat(
            DownloadFilenameResolver.resolve(
                url = "https://hermes.example/download/",
                mimeType = "application/octet-stream"
            )
        ).isEqualTo("hermes-download")
    }
}
