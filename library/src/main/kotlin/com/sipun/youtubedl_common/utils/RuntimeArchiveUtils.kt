package com.sipun.youtubedl_common.utils

import org.apache.commons.io.FileUtils
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

object RuntimeArchiveUtils {
    fun sha256(file: File): String {
        if (!file.isFile) {
            throw IOException("Runtime asset is missing: ${file.path}")
        }

        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Checks the executable marker of a standalone yt-dlp asset.
     *
     * Full ZIP integrity and entry-point checks run in CI against the bundled resource.
     * Keep the Android runtime check lightweight because yt-dlp is a Python launcher with
     * an archive appended after its source preamble.
     */
    fun hasYtDlpShebang(file: File): Boolean {
        return !(!file.isFile || file.length() < 4L) && try {
            file.inputStream().use { input ->
                input.read() == '#'.code && input.read() == '!'.code
            }
        } catch (_: Exception) {
            false
        }
    }

    fun installArchive(archive: File, target: File, validate: (File) -> Unit) {
        if (!archive.isFile || archive.length() == 0L) {
            throw IOException("Runtime archive is missing or empty")
        }

        val parent = target.absoluteFile.parentFile
            ?: throw IOException("Runtime target has no parent")
        if (!parent.exists() && !parent.mkdirs()) {
            throw IOException("Could not create runtime parent directory")
        }

        val token = UUID.randomUUID().toString()
        val staged = File(parent, ".${target.name}.staging-$token")
        val backup = File(parent, ".${target.name}.backup-$token")

        try {
            if (!staged.mkdirs()) {
                throw IOException("Could not create runtime staging directory")
            }
            ZipUtils.unzip(archive, staged)
            validate(staged)

            if (target.exists() && !target.renameTo(backup)) {
                throw IOException("Could not preserve existing runtime directory")
            }
            if (!staged.renameTo(target)) {
                if (backup.exists()) backup.renameTo(target)
                throw IOException("Could not activate validated runtime directory")
            }
            FileUtils.deleteQuietly(backup)
        } catch (error: Exception) {
            FileUtils.deleteQuietly(staged)
            if (!target.exists() && backup.exists()) backup.renameTo(target)
            throw error
        } finally {
            FileUtils.deleteQuietly(staged)
        }
    }
}
