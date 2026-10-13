package com.sipun.youtubedl_android

import android.content.Context
import com.fasterxml.jackson.databind.JsonNode
import com.sipun.youtubedl_android.YoutubeDL.UpdateChannel
import com.sipun.youtubedl_android.YoutubeDL.UpdateStatus
import com.sipun.youtubedl_common.SharedPrefsHelper
import com.sipun.youtubedl_common.SharedPrefsHelper.update
import com.sipun.youtubedl_common.utils.RuntimeArchiveUtils
import org.apache.commons.io.FileUtils
import java.io.File
import java.io.IOException
import java.net.URL
import java.util.regex.Pattern

internal object YoutubeDLUpdater {
    private const val binaryName = "yt-dlp"
    private const val versionKey = "dlpVersion"
    private const val versionNameKey = "dlpVersionName"

    internal fun update(
        appContext: Context?,
        channel: UpdateChannel = UpdateChannel.STABLE
    ): UpdateStatus {
        val context = appContext ?: throw IllegalArgumentException("Context is required")
        val json = checkForUpdate(context, channel)
            ?: return UpdateStatus.ALREADY_UP_TO_DATE
        val tag = getTag(json)
        val name = json["name"]?.asText()?.takeIf { it.isNotBlank() } ?: tag
        val (url, digest) = getAsset(json, channel)
        val downloaded = download(context, url)
        val directory = getDir(context)

        if (!directory.exists() && !directory.mkdirs()) {
            FileUtils.deleteQuietly(downloaded)
            throw IOException("Could not create yt-dlp directory")
        }

        val binary = File(directory, binaryName)
        val staged = File.createTempFile("yt-dlp-", ".staged", directory)
        val backup = File(directory, "$binaryName.previous")
        try {
            if (digest != null && RuntimeArchiveUtils.sha256(downloaded) != digest) {
                throw YoutubeDLException("Downloaded yt-dlp SHA-256 does not match release metadata")
            }
            if (!RuntimeArchiveUtils.hasYtDlpShebang(downloaded)) {
                throw YoutubeDLException("Downloaded yt-dlp asset failed archive validation")
            }

            FileUtils.copyFile(downloaded, staged)
            if (!RuntimeArchiveUtils.hasYtDlpShebang(staged)) {
                throw YoutubeDLException("Staged yt-dlp asset failed validation")
            }

            if (backup.exists()) FileUtils.deleteQuietly(backup)
            if (binary.exists() && !binary.renameTo(backup)) {
                throw IOException("Could not preserve existing yt-dlp binary")
            }
            if (!staged.renameTo(binary)) {
                if (backup.exists()) backup.renameTo(binary)
                throw IOException("Could not activate updated yt-dlp binary")
            }

            FileUtils.deleteQuietly(backup)
            update(context, versionKey, tag)
            update(context, versionNameKey, name)
        } catch (error: Exception) {
            if (!binary.exists() && backup.exists()) backup.renameTo(binary)
            if (error is YoutubeDLException) throw error
            throw YoutubeDLException("Failed to install yt-dlp update", error)
        } finally {
            FileUtils.deleteQuietly(downloaded)
            FileUtils.deleteQuietly(staged)
        }

        return UpdateStatus.DONE
    }

    private fun checkForUpdate(context: Context, channel: UpdateChannel): JsonNode? {
        val connection = URL(channel.apiUrl).openConnection().apply {
            connectTimeout = 5000
            readTimeout = 10000
        }
        val json = connection.getInputStream().use { YoutubeDL.objectMapper.readTree(it) }
        val tag = getTag(json)
        val installed = File(getDir(context), binaryName)
        return if (
            tag == SharedPrefsHelper[context, versionKey] &&
            RuntimeArchiveUtils.hasYtDlpShebang(installed)
        ) {
            null
        } else {
            json
        }
    }

    private fun getTag(json: JsonNode): String =
        json["tag_name"]?.asText()?.takeIf { it.isNotBlank() }
            ?: throw YoutubeDLException("Release metadata has no tag")

    private fun getAsset(json: JsonNode, channel: UpdateChannel): Pair<String, String?> {
        val parts = URL(channel.apiUrl).path.split("/").filter { it.isNotEmpty() }
        val owner = parts.getOrNull(1) ?: throw YoutubeDLException("Invalid update channel URL")
        val repository =
            parts.getOrNull(2) ?: throw YoutubeDLException("Invalid update channel URL")
        val assets = json["assets"]
        if (assets == null || !assets.isArray) {
            throw YoutubeDLException("Release metadata has no assets")
        }

        val asset = assets.firstOrNull { it["name"]?.asText() == binaryName }
            ?: throw YoutubeDLException("Unable to find yt-dlp release asset")
        val url = asset["browser_download_url"]?.asText().orEmpty()
        val parsedUrl = URL(url)
        if (
            parsedUrl.protocol != "https" ||
            parsedUrl.host != "github.com" ||
            !parsedUrl.path.startsWith("/$owner/$repository/releases/download/")
        ) {
            throw YoutubeDLException("Unexpected yt-dlp release asset URL")
        }

        val digest = asset["digest"]?.asText().orEmpty()
        val expectedDigest = if (digest.isEmpty()) {
            null
        } else {
            val matcher = Pattern.compile("sha256:([0-9a-fA-F]{64})").matcher(digest)
            if (!matcher.matches()) {
                throw YoutubeDLException("Invalid yt-dlp release SHA-256 metadata")
            }
            matcher.group(1)?.lowercase()
                ?: throw YoutubeDLException("Invalid release SHA-256 metadata")
        }

        return url to expectedDigest
    }

    private fun download(context: Context, url: String): File {
        val connection = URL(url).openConnection().apply {
            connectTimeout = 5000
            readTimeout = 15000
        }
        val file = File.createTempFile(binaryName, null, context.cacheDir)
        try {
            connection.getInputStream().use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
            if (file.length() == 0L) throw IOException("Downloaded yt-dlp asset is empty")
            return file
        } catch (error: Exception) {
            FileUtils.deleteQuietly(file)
            throw error
        }
    }

    private fun getDir(context: Context): File =
        File(File(context.noBackupFilesDir, YoutubeDL.baseName), YoutubeDL.ytdlpDirName)

    fun version(appContext: Context?): String? = SharedPrefsHelper[appContext!!, versionKey]

    fun versionName(appContext: Context?): String? = SharedPrefsHelper[appContext!!, versionNameKey]
}
