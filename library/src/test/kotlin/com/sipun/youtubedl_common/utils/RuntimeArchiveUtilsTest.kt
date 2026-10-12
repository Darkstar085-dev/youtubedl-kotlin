package com.sipun.youtubedl_common.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class RuntimeArchiveUtilsTest {
    @Test
    fun acceptsYtDlpLauncherWithAppendedArchive() {
        val directory = Files.createTempDirectory("ytdlp-zipapp").toFile()
        try {
            val file = File(directory, "ytdlp")
            file.outputStream().use { output ->
                output.write(
                    "#!/usr/bin/env python3\n# standalone launcher code before the archive\n".toByteArray()
                )
                ZipOutputStream(output).use { zip ->
                    zip.putNextEntry(ZipEntry("__main__.py"))
                    zip.write("from yt_dlp import main".toByteArray())
                    zip.closeEntry()
                    zip.putNextEntry(ZipEntry("yt_dlp/__init__.py"))
                    zip.write("__version__ = \"test\"".toByteArray())
                    zip.closeEntry()
                }
            }

            assertTrue(RuntimeArchiveUtils.hasYtDlpShebang(file))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun bundledYtDlpHasExecutableHeader() {
        val asset = File("src/main/res/raw/ytdlp")
        assertTrue("Bundled yt-dlp asset is missing: ${asset.absolutePath}", asset.isFile)
        assertTrue(
            "Bundled yt-dlp asset failed validation",
            RuntimeArchiveUtils.hasYtDlpShebang(asset)
        )
    }

    @Test
    fun rejectsYtDlpWithoutExecutableHeader() {
        val directory = Files.createTempDirectory("ytdlp-invalid").toFile()
        try {
            val file = File(directory, "ytdlp").apply { writeText("not-a-zip") }
            assertFalse(RuntimeArchiveUtils.hasYtDlpShebang(file))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun sha256DetectsSameLengthContentChanges() {
        val directory = Files.createTempDirectory("runtime-hash").toFile()
        try {
            val file = File(directory, "asset").apply { writeText("abcd") }
            val originalHash = RuntimeArchiveUtils.sha256(file)

            file.writeText("wxyz")

            assertEquals(4L, file.length())
            assertNotEquals(originalHash, RuntimeArchiveUtils.sha256(file))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun failedValidationPreservesExistingRuntime() {
        val directory = Files.createTempDirectory("runtime-stage").toFile()
        try {
            val archive = File(directory, "runtime.zip")
            ZipOutputStream(archive.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("usr/lib/new.so"))
                zip.write(byteArrayOf(1, 2, 3))
                zip.closeEntry()
            }

            val target = File(directory, "runtime")
            File(target, "usr/lib").mkdirs()
            File(target, "usr/lib/old.so").writeText("old")

            try {
                RuntimeArchiveUtils.installArchive(archive, target) {
                    throw IllegalStateException("simulated failure")
                }
            } catch (_: IllegalStateException) {
                // Expected: a failed validation must leave the old runtime intact.
            }

            assertTrue(File(target, "usr/lib/old.so").isFile)
            assertFalse(File(target, "usr/lib/new.so").exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun installsValidatedArchive() {
        val directory = Files.createTempDirectory("runtime-install").toFile()
        try {
            val archive = File(directory, "runtime.zip")
            ZipOutputStream(archive.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("usr/lib/runtime.so"))
                zip.write(byteArrayOf(4, 5, 6))
                zip.closeEntry()
            }

            val target = File(directory, "runtime")
            RuntimeArchiveUtils.installArchive(archive, target) { staged ->
                assertTrue(File(staged, "usr/lib/runtime.so").isFile)
            }

            assertTrue(File(target, "usr/lib/runtime.so").isFile)
        } finally {
            directory.deleteRecursively()
        }
    }
}
