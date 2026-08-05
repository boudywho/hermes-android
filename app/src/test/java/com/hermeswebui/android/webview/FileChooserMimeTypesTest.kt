package com.hermeswebui.android.webview

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FileChooserMimeTypesTest {
    @Test
    fun `mixed comma-separated MIME types and extensions fall back to all files`() {
        val result = FileChooserMimeTypes.normalize(
            arrayOf("image/*, text/*, application/pdf, .md, .py, .docx, .zip")
        )

        assertThat(result.asList()).containsExactly("*/*")
    }

    @Test
    fun `valid mixed MIME types are split normalized and de-duplicated`() {
        val result = FileChooserMimeTypes.normalize(
            arrayOf(" IMAGE/*, text/plain;APPLICATION/PDF ", "text/plain")
        )

        assertThat(result.asList()).containsExactly(
            "image/*",
            "text/plain",
            "application/pdf"
        ).inOrder()
    }

    @Test
    fun `empty accept input falls back to all files`() {
        assertThat(FileChooserMimeTypes.normalize(emptyArray()).asList())
            .containsExactly("*/*")
    }

    @Test
    fun `image detection handles comma-separated accept input`() {
        assertThat(
            FileChooserMimeTypes.requestsImage(arrayOf("text/plain, image/png, application/pdf"))
        ).isTrue()
        assertThat(FileChooserMimeTypes.requestsImage(arrayOf("text/plain, */*"))).isTrue()
        assertThat(FileChooserMimeTypes.requestsImage(emptyArray())).isTrue()
    }

    @Test
    fun `image detection rejects non-image and extension-only input`() {
        assertThat(
            FileChooserMimeTypes.requestsImage(arrayOf("text/plain, application/pdf"))
        ).isFalse()
        assertThat(FileChooserMimeTypes.requestsImage(arrayOf(".png, .jpg"))).isFalse()
    }
}
