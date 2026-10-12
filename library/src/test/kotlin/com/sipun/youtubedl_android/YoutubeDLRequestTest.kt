package com.sipun.youtubedl_android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YoutubeDLRequestTest {
    @Test
    fun preservesRepeatedOptionsAndReturnsTheFirstArgument() {
        val request = YoutubeDLRequest("https://example.invalid/video")
            .addOption("-f", "best")
            .addOption("-f", "bestvideo*+bestaudio/best")

        assertEquals(
            listOf(
                "-f", "best",
                "-f", "bestvideo*+bestaudio/best",
                "https://example.invalid/video",
            ),
            request.buildCommand(),
        )
        assertEquals("best", request.getOption("-f"))
        assertEquals(listOf("best", "bestvideo*+bestaudio/best"), request.getArguments("-f"))
        assertNull(request.getOption("--missing"))
        assertTrue(request.hasOption("-f"))
    }

    @Test
    fun buildsFlagOnlyAndNumericOptions() {
        val request = YoutubeDLRequest("https://example.invalid/video")
            .addOption("--no-warnings")
            .addOption("--retries", 3)

        assertEquals(
            listOf("--no-warnings", "--retries", "3", "https://example.invalid/video"),
            request.buildCommand(),
        )
    }

    @Test
    fun placesCustomCommandsAfterOptionsAndBeforeUrls() {
        val request = YoutubeDLRequest(listOf("first", "second"))
            .addOption("--socket-timeout", 30)
            .addCommands(listOf("--ignore-errors", "--newline"))

        assertEquals(
            listOf("--socket-timeout", "30", "--ignore-errors", "--newline", "first", "second"),
            request.buildCommand(),
        )
    }
}
