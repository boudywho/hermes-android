package com.hermeswebui.android

import com.google.common.truth.Truth.assertThat
import com.hermeswebui.android.webview.BlobDownloadProtocol
import org.junit.Test

class BlobDownloadProtocolTest {
    @Test
    fun `accepts bounded start and ordered chunk shapes`() {
        assertThat(
            BlobDownloadProtocol.parse(
                """{"type":"start","id":"abc_123","filename":"export.json","mime":"application/json","size":42}"""
            )
        ).isInstanceOf(BlobDownloadProtocol.Message.Start::class.java)
        assertThat(
            BlobDownloadProtocol.parse(
                """{"type":"chunk","id":"abc_123","sequence":0,"data":"AQID"}"""
            )
        ).isEqualTo(BlobDownloadProtocol.Message.Chunk("abc_123", 0, "AQID"))
    }

    @Test
    fun `normalizes parameterized blob mime to its strict base type`() {
        assertThat(
            BlobDownloadProtocol.parse(
                """{"type":"start","id":"csv","filename":"export.csv","mime":" Text/CSV ;charset=utf-8","size":42}"""
            )
        ).isEqualTo(
            BlobDownloadProtocol.Message.Start(
                id = "csv",
                filename = "export.csv",
                mime = "text/csv",
                size = 42
            )
        )
    }

    @Test
    fun `rejects oversized malformed and ambiguous messages`() {
        assertThat(
            BlobDownloadProtocol.parse(
                """{"type":"start","id":"bad id","filename":"x","mime":"text/plain","size":1}"""
            )
        ).isNull()
        assertThat(
            BlobDownloadProtocol.parse(
                """{"type":"start","id":"ok","filename":"x","mime":"not-a-mime","size":1}"""
            )
        ).isNull()
        assertThat(
            BlobDownloadProtocol.parse(
                """{"type":"start","id":"ok","filename":"x","mime":"text/plain\u0000;charset=utf-8","size":1}"""
            )
        ).isNull()
        assertThat(
            BlobDownloadProtocol.parse(
                """{"type":"start","id":"ok","filename":"x","mime":"not a/type;charset=utf-8","size":1}"""
            )
        ).isNull()
        assertThat(
            BlobDownloadProtocol.parse(
                """{"type":"finish","id":"ok","extra":true}"""
            )
        ).isNull()
        assertThat(
            BlobDownloadProtocol.parse(
                """{"type":"start","id":"ok","filename":"x","mime":"text/plain","size":${BlobDownloadProtocol.MAX_TOTAL_BYTES + 1}}"""
            )
        ).isNull()
    }
}
