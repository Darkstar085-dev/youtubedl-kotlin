package com.sipun.ffmpeg

import android.content.Context
import com.sipun.youtubedl_android.YoutubeDLException
import com.sipun.youtubedl_common.SharedPrefsHelper
import com.sipun.youtubedl_common.SharedPrefsHelper.update
import com.sipun.youtubedl_common.utils.RuntimeArchiveUtils
import java.io.File
import java.io.IOException

object FFmpeg {
    private var initialized = false
    private var binDir: File? = null

    @Synchronized
    fun init(appContext: Context) {
        if (initialized) return

        val baseDir = File(appContext.noBackupFilesDir, baseName)
        if (!baseDir.exists()) baseDir.mkdir()
        binDir = File(appContext.applicationInfo.nativeLibraryDir)
        val packagesDir = File(baseDir, packagesRoot)
        val ffmpegDir = File(packagesDir, ffmegDirName)

        initFFmpeg(appContext, ffmpegDir)
        initialized = true
    }

    private fun initFFmpeg(appContext: Context, ffmpegDir: File) {
        val archive = File(binDir, ffmpegLibName)
        if (!archive.isFile || archive.length() == 0L) {
            throw YoutubeDLException("FFmpeg runtime archive is missing or empty")
        }

        val version = RuntimeArchiveUtils.sha256(archive)
        val libraryDirectory = File(ffmpegDir, "usr/lib")
        val needsInstall = !libraryDirectory.isDirectory ||
                libraryDirectory.list()?.isEmpty() != false ||
                version != SharedPrefsHelper[appContext, ffmpegLibVersion]

        if (needsInstall) {
            try {
                RuntimeArchiveUtils.installArchive(archive, ffmpegDir) { staged ->
                    val stagedLibraries = File(staged, "usr/lib")
                    if (!stagedLibraries.isDirectory || stagedLibraries.list()
                            ?.isEmpty() != false
                    ) {
                        throw IOException("FFmpeg archive contains no runtime libraries")
                    }
                }
            } catch (error: Exception) {
                throw YoutubeDLException("failed to initialize", error)
            }
            update(appContext, ffmpegLibVersion, version)
        }
    }

    @JvmStatic
    fun getInstance() = this

    private const val baseName = "youtubedl-android"
    private const val packagesRoot = "packages"
    private const val ffmegDirName = "ffmpeg"
    private const val ffmpegLibName = "libffmpeg.zip.so"
    private const val ffmpegLibVersion = "ffmpegLibVersion"
}
