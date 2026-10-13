package com.sipun.aria2c

import android.content.Context
import com.sipun.youtubedl_android.YoutubeDLException
import com.sipun.youtubedl_common.SharedPrefsHelper
import com.sipun.youtubedl_common.utils.RuntimeArchiveUtils
import java.io.File
import java.io.IOException

object Aria2c {
    private var runtimeArchiveDirectory: File? = null

    /** Configure downloaded runtime archives before calling init(). */
    @JvmStatic
    fun setRuntimeArchiveDirectory(directory: File?) {
        check(!initialized) { "Runtime archive directory must be set before initialization" }
        runtimeArchiveDirectory = directory
    }

    private var initialized = false
    private var binDir: File? = null

    @Synchronized
    fun init(appContext: Context) {
        if (initialized) return

        val baseDir = File(appContext.noBackupFilesDir, baseName)
        if (!baseDir.exists()) baseDir.mkdir()
        binDir = File(appContext.applicationInfo.nativeLibraryDir)
        val packagesDir = File(baseDir, packagesRoot)
        val aria2cDir = File(packagesDir, aria2cDirName)

        initAria2c(appContext, aria2cDir)
        initialized = true
    }

    private fun initAria2c(appContext: Context, aria2cDir: File) {
        val archive = File(runtimeArchiveDirectory ?: binDir!!, aria2cLibName)
        if (!archive.exists()) return
        if (!archive.isFile || archive.length() == 0L) {
            throw YoutubeDLException("aria2 runtime archive is empty")
        }

        val version = RuntimeArchiveUtils.sha256(archive)
        val libraryDirectory = File(aria2cDir, "usr/lib")
        val needsInstall = !libraryDirectory.isDirectory ||
                libraryDirectory.list()?.isEmpty() != false ||
                version != SharedPrefsHelper.get(appContext, aria2cLibVersion)

        if (needsInstall) {
            try {
                RuntimeArchiveUtils.installArchive(archive, aria2cDir) { staged ->
                    val stagedLibraries = File(staged, "usr/lib")
                    if (!stagedLibraries.isDirectory || stagedLibraries.list()
                            ?.isEmpty() != false
                    ) {
                        throw IOException("aria2 archive contains no runtime libraries")
                    }
                }
            } catch (error: Exception) {
                throw YoutubeDLException("failed to initialize", error)
            }
            updateAria2c(appContext, version)
        }
    }

    private fun updateAria2c(appContext: Context, version: String) {
        SharedPrefsHelper.update(appContext, aria2cLibVersion, version)
    }

    @JvmStatic
    fun getInstance() = this

    private const val baseName = "youtubedl-android"
    private const val packagesRoot = "packages"
    private const val aria2cDirName = "aria2c"
    private const val aria2cLibName = "libaria2c.zip.so"
    private const val aria2cLibVersion = "aria2cLibVersion"
}
