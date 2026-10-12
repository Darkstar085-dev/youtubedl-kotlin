package com.sipun.youtubedl_android

import android.content.Context
import android.os.Build
import com.fasterxml.jackson.databind.ObjectMapper
import com.sipun.youtubedl_android.mapper.VideoInfo
import com.sipun.youtubedl_common.SharedPrefsHelper
import com.sipun.youtubedl_common.SharedPrefsHelper.update
import com.sipun.youtubedl_common.utils.RuntimeArchiveUtils
import org.apache.commons.io.FileUtils
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.concurrent.TimeUnit

object YoutubeDL {
    private var initialized = false
    private var pythonPath: File? = null
    private var ffmpegPath: File? = null
    private var quickJsPath: File? = null
    private var ytdlpPath: File? = null
    private var binDir: File? = null
    private var ENV_LD_LIBRARY_PATH: String? = null
    private var ENV_SSL_CERT_FILE: String? = null
    private var ENV_OPENSSL_CONF: String? = null
    private var ENV_OPENSSL_MODULES: String? = null
    private var ENV_PYTHONHOME: String? = null
    private var TMPDIR: String = ""
    private val idProcessMap = Collections.synchronizedMap(HashMap<String, Process>())

    @Synchronized
    @Throws(YoutubeDLException::class)
    fun init(appContext: Context) {
        if (initialized) return
        var stage = "prepare_directories"
        try {
            val baseDir = File(appContext.noBackupFilesDir, baseName)
            if (!baseDir.exists() && !baseDir.mkdirs()) {
                throw IOException("Could not create base directory: ${baseDir.absolutePath}")
            }
            val packagesDir = File(baseDir, packagesRoot)
            binDir = File(appContext.applicationInfo.nativeLibraryDir)
            pythonPath = File(binDir, pythonBinName)
            quickJsPath = File(binDir, quickJsBinName)
            val nativeFfmpegPath = File(binDir, ffmpegBinName)
            val nativeFfprobePath = File(binDir, ffprobeBinName)
            val pythonDir = File(packagesDir, pythonDirName)
            val ffmpegDir = File(packagesDir, ffmpegDirName)
            val aria2cDir = File(packagesDir, aria2cDirName)
            val ytdlpDir = File(baseDir, ytdlpDirName)
            ytdlpPath = File(ytdlpDir, ytdlpBin)
            // Execute binaries from Android's installed native-library directory.
            // Writable app-private data directories can be mounted noexec on Android.
            ffmpegPath = nativeFfmpegPath
            val ffprobePath = nativeFfprobePath
            // Prefer the native toolchains' bundled dependencies when ffmpeg/aria2
            // are spawned, then expose Python's runtime libraries as a fallback.
            ENV_LD_LIBRARY_PATH = ffmpegDir.absolutePath + "/usr/lib" + ":" +
                    aria2cDir.absolutePath + "/usr/lib" + ":" +
                    pythonDir.absolutePath + "/usr/lib"
            ENV_SSL_CERT_FILE = pythonDir.absolutePath + "/usr/etc/tls/cert.pem"
            ENV_OPENSSL_CONF = pythonDir.absolutePath + "/usr/etc/tls/openssl.cnf"
            ENV_OPENSSL_MODULES = pythonDir.absolutePath + "/usr/lib/ossl-modules"
            ENV_PYTHONHOME = pythonDir.absolutePath + "/usr"
            TMPDIR = appContext.cacheDir.absolutePath

            stage = "native_library_check"
            val nativeFiles = listOf(
                pythonPath!!,
                nativeFfmpegPath,
                nativeFfprobePath,
                quickJsPath!!,
                File(binDir, pythonLibName)
            )
            val missing = nativeFiles.filter { !it.isFile || it.length() == 0L }
            if (missing.isNotEmpty()) {
                throw IOException("Missing/empty native files: " + missing.joinToString { "${it.name} (exists=${it.exists()}, size=${it.length()})" })
            }
            stage = "python_runtime"
            initPython(appContext, pythonDir)
            stage = "ffmpeg_runtime"
            verifyExecutable(ffmpegPath!!, listOf("-version"), "ffmpeg")
            verifyExecutable(ffprobePath, listOf("-version"), "ffprobe")
            stage = "yt_dlp_asset"
            init_ytdlp(appContext, ytdlpDir)
            initialized = true
        } catch (e: Exception) {
            val chain = generateSequence(e as Throwable?) { it.cause }
                .take(4)
                .map { cause ->
                    val message = cause.message?.takeIf { it.isNotBlank() }
                    if (message == null) cause.javaClass.simpleName else "${cause.javaClass.simpleName}: $message"
                }
                .joinToString(" <- ")
            val detail = chain.ifBlank { e.javaClass.simpleName }.take(900)
            throw YoutubeDLException("Initialization failed at $stage: $detail", e)
        }
    }

    @Throws(YoutubeDLException::class)
    fun init_ytdlp(appContext: Context, ytdlpDir: File) {
        if (!ytdlpDir.exists() && !ytdlpDir.mkdirs()) {
            throw YoutubeDLException("failed to create yt-dlp directory")
        }
        val binary = File(ytdlpDir, ytdlpBin)
        if (RuntimeArchiveUtils.hasYtDlpShebang(binary)) return
        val staged = File.createTempFile("ytdlp-", ".tmp", ytdlpDir)
        val backup = File(ytdlpDir, "$ytdlpBin.previous")
        try {
            appContext.resources.openRawResource(R.raw.ytdlp).use { input ->
                FileUtils.copyInputStreamToFile(input, staged)
            }
            if (!RuntimeArchiveUtils.hasYtDlpShebang(staged)) {
                throw IOException("Bundled yt-dlp asset failed validation")
            }
            if (backup.exists()) FileUtils.deleteQuietly(backup)
            if (binary.exists() && !binary.renameTo(backup)) {
                throw IOException("Could not preserve current yt-dlp binary")
            }
            if (!staged.renameTo(binary)) {
                if (backup.exists()) backup.renameTo(binary)
                throw IOException("Could not install bundled yt-dlp binary")
            }
            FileUtils.deleteQuietly(backup)
        } catch (e: Exception) {
            val detail = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
            throw YoutubeDLException("failed to initialize yt-dlp: $detail", e)
        } finally {
            FileUtils.deleteQuietly(staged)
        }
    }

    @Throws(YoutubeDLException::class)
    fun initPython(appContext: Context, pythonDir: File) {
        val archive = File(binDir, pythonLibName)
        if (!archive.isFile || archive.length() == 0L) {
            throw YoutubeDLException("Python runtime archive is missing or empty")
        }
        val version = RuntimeArchiveUtils.sha256(archive)
        if (!isPythonRuntimeValid(pythonDir) || version != SharedPrefsHelper[appContext, pythonLibVersion]) {
            try {
                RuntimeArchiveUtils.installArchive(archive, pythonDir) { staged ->
                    if (!isPythonRuntimeValid(staged)) {
                        throw IOException("Python runtime archive is missing its standard library")
                    }
                }
            } catch (e: Exception) {
                val detail = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                throw YoutubeDLException("failed to initialize Python runtime: $detail", e)
            }
            update(appContext, pythonLibVersion, version)
        }
    }

    private fun verifyExecutable(binary: File, arguments: List<String>, expectedName: String) {
        val process = ProcessBuilder(listOf(binary.absolutePath) + arguments)
            .redirectErrorStream(true)
            .apply {
                environment().apply {
                    ENV_LD_LIBRARY_PATH?.let { this["LD_LIBRARY_PATH"] = it }
                    ENV_SSL_CERT_FILE?.let { this["SSL_CERT_FILE"] = it }
                    ENV_OPENSSL_CONF?.let { this["OPENSSL_CONF"] = it }
                    ENV_OPENSSL_MODULES?.let { this["OPENSSL_MODULES"] = it }
                }
            }
            .start()
        if (!process.waitFor(15, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw IOException("Timed out checking bundled executable ${binary.name}")
        }
        val output = process.inputStream.bufferedReader().use { it.readText() }.take(600)
        val exitCode = process.exitValue()
        if (exitCode != 0 || !output.contains(expectedName, ignoreCase = true)) {
            throw IOException(
                "Bundled executable ${binary.name} failed its version check (exit $exitCode): " +
                        output.ifBlank { "no output" }
            )
        }
    }

    private fun isPythonRuntimeValid(directory: File): Boolean {
        val root = File(directory, "usr/lib")
        val versions = root.listFiles()?.filter {
            it.isDirectory && it.name.matches(Regex("python3\\.\\d+"))
        }.orEmpty()
        return versions.any { File(it, "encodings/__init__.py").isFile }
    }

    private fun assertInit() {
        check(initialized) { "instance not initialized" }
    }

    @Throws(YoutubeDLException::class, InterruptedException::class, CanceledException::class)
    fun getInfo(url: String): VideoInfo {
        val request = YoutubeDLRequest(url)
        return getInfo(request)
    }

    @Throws(YoutubeDLException::class, InterruptedException::class, CanceledException::class)
    fun getInfo(request: YoutubeDLRequest): VideoInfo {
        request.addOption("--dump-json")
        val response = execute(request, null, null)
        val videoInfo: VideoInfo = try {
            objectMapper.readValue(response.out, VideoInfo::class.java)
        } catch (e: IOException) {
            throw YoutubeDLException("Unable to parse video information", e)
        } ?: throw YoutubeDLException("Failed to fetch video information")
        return videoInfo
    }

    private fun ignoreErrors(request: YoutubeDLRequest, out: String): Boolean {
        return request.hasOption("--dump-json") && !out.isEmpty() && request.hasOption("--ignore-errors")
    }

    fun destroyProcessById(id: String): Boolean {
        val process = synchronized(idProcessMap) { idProcessMap.remove(id) } ?: return false
        terminateProcessTree(process)
        return true
    }

    private fun terminateProcessTree(process: Process) {
        val pid = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                (process.javaClass.getMethod("pid").invoke(process) as Number).toInt()
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
        if (pid != null && pid > 0) terminateDescendants(pid)
        try {
            process.destroy()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && process.isAlive) process.destroyForcibly()
        } catch (_: Exception) {
        }
        try {
            process.inputStream.close()
        } catch (_: IOException) {
        }
        try {
            process.errorStream.close()
        } catch (_: IOException) {
        }
        try {
            process.outputStream.close()
        } catch (_: IOException) {
        }
    }

    private fun terminateDescendants(pid: Int) {
        val children = try {
            File("/proc/$pid/task/$pid/children").readText().trim().split(Regex("\\s+"))
                .filter { it.isNotBlank() }.mapNotNull { it.toIntOrNull() }
        } catch (_: Exception) {
            emptyList()
        }
        children.forEach(::terminateDescendants)
        children.forEach { childPid ->
            try {
                android.os.Process.sendSignal(childPid, 15)
            } catch (_: Exception) {
                // A child may exit before its parent is cancelled.
            }
        }
    }

    class CanceledException : Exception()

    @Throws(YoutubeDLException::class, InterruptedException::class, CanceledException::class)
    fun execute(
        request: YoutubeDLRequest,
        processId: String? = null,
        callback: ((Float, Long, String) -> Unit)? = null
    ): YoutubeDLResponse {
        return executeImpl(request, processId, false, callback)
    }

    @JvmOverloads
    @Throws(YoutubeDLException::class, InterruptedException::class, CanceledException::class)
    fun execute(
        request: YoutubeDLRequest,
        processId: String? = null,
        redirectErrorStream: Boolean = false,
        callback: ((Float, Long, String) -> Unit)? = null
    ): YoutubeDLResponse {
        return executeImpl(request, processId, redirectErrorStream, callback)
    }

    @Throws(YoutubeDLException::class, InterruptedException::class, CanceledException::class)
    private fun executeImpl(
        request: YoutubeDLRequest,
        processId: String? = null,
        redirectErrorStream: Boolean = false,
        callback: ((Float, Long, String) -> Unit)? = null
    ): YoutubeDLResponse {
        assertInit()
        if (processId != null && synchronized(idProcessMap) { idProcessMap.containsKey(processId) }) {
            throw YoutubeDLException("Process ID already exists")
        }
        // disable caching unless explicitly requested
        if (!request.hasOption("--cache-dir") || request.getOption("--cache-dir") == null) {
            request.addOption("--no-cache-dir")
        }

        if (request.buildCommand()
                .any { it.substringAfterLast('/').equals("libaria2c.so", ignoreCase = true) }
        ) {
            request
                .addOption("--external-downloader-args", "aria2c:--summary-interval=1")
                .addOption(
                    "--external-downloader-args",
                    "aria2c:--ca-certificate=$ENV_SSL_CERT_FILE"
                )
        }

        request.addOption("--js-runtimes", "quickjs:${quickJsPath!!.absolutePath}")

        /* Set ffmpeg location, See https://github.com/xibr/ytdlp-lazy/issues/1 */
        request.addOption("--ffmpeg-location", ffmpegPath!!.absolutePath)
        val youtubeDLResponse: YoutubeDLResponse
        val process: Process
        val exitCode: Int
        val outBuffer = StringBuffer() //stdout
        val errBuffer = StringBuffer() //stderr
        val startTime = System.currentTimeMillis()
        val args = request.buildCommand()
        val command: MutableList<String?> = ArrayList()
        command.addAll(listOf(pythonPath!!.absolutePath, ytdlpPath!!.absolutePath))
        command.addAll(args)
        val processBuilder = ProcessBuilder(command)
            .redirectErrorStream(redirectErrorStream)

        processBuilder.environment().apply {
            this["LD_LIBRARY_PATH"] = ENV_LD_LIBRARY_PATH
            this["SSL_CERT_FILE"] = ENV_SSL_CERT_FILE
            this["OPENSSL_CONF"] = ENV_OPENSSL_CONF
            this["OPENSSL_MODULES"] = ENV_OPENSSL_MODULES
            this["PATH"] = System.getenv("PATH") + ":" + binDir!!.absolutePath
            this["PYTHONHOME"] = ENV_PYTHONHOME
            this["HOME"] = ENV_PYTHONHOME
            this["TMPDIR"] = TMPDIR
        }

        process = try {
            processBuilder.start()
        } catch (e: IOException) {
            throw YoutubeDLException(e)
        }
        if (processId != null) {
            synchronized(idProcessMap) {
                idProcessMap[processId] = process
            }
        }
        val outStream = process.inputStream
        val errStream = process.errorStream
        val stdOutProcessor = StreamProcessExtractor(outBuffer, outStream, callback)
        val stdErrProcessor = StreamGobbler(errBuffer, errStream)
        exitCode = try {
            // Drain both pipes while the process runs to avoid a full-pipe deadlock.
            stdOutProcessor.join()
            stdErrProcessor.join()
            process.waitFor()
        } catch (e: InterruptedException) {
            terminateProcessTree(process)
            try {
                outStream.close()
            } catch (_: IOException) {
            }
            try {
                errStream.close()
            } catch (_: IOException) {
            }
            stdOutProcessor.interrupt()
            stdErrProcessor.interrupt()
            if (processId != null) {
                synchronized(idProcessMap) { idProcessMap.remove(processId, process) }
            }
            throw e
        }
        val out = outBuffer.toString()
        val err = errBuffer.toString()
        if (processId != null && synchronized(idProcessMap) { !idProcessMap.containsKey(processId) }) {
            throw CanceledException()
        }
        if (exitCode > 0) {
            if (!ignoreErrors(request, out)) {
                if (processId != null) {
                    synchronized(idProcessMap) { idProcessMap.remove(processId, process) }
                }
                throw YoutubeDLException(err)
            }
        }
        if (processId != null) synchronized(idProcessMap) {
            idProcessMap.remove(
                processId,
                process
            )
        }

        val elapsedTime = System.currentTimeMillis() - startTime
        youtubeDLResponse = YoutubeDLResponse(command, exitCode, elapsedTime, out, err)
        return youtubeDLResponse
    }

    @Synchronized
    @Throws(YoutubeDLException::class)
    fun updateYoutubeDL(
        appContext: Context,
        updateChannel: UpdateChannel = UpdateChannel.STABLE
    ): UpdateStatus? {
        assertInit()
        return try {
            YoutubeDLUpdater.update(appContext, updateChannel)
        } catch (e: IOException) {
            throw YoutubeDLException("failed to update youtube-dl", e)
        }
    }

    fun version(appContext: Context?): String? {
        return YoutubeDLUpdater.version(appContext)
    }

    fun versionName(appContext: Context?): String? {
        return YoutubeDLUpdater.versionName(appContext)
    }

    enum class UpdateStatus {
        DONE, ALREADY_UP_TO_DATE
    }

    open class UpdateChannel(val apiUrl: String) {
        object STABLE : UpdateChannel("https://api.github.com/repos/yt-dlp/yt-dlp/releases/latest")
        object NIGHTLY :
            UpdateChannel("https://api.github.com/repos/yt-dlp/yt-dlp-nightly-builds/releases/latest")

        object MASTER :
            UpdateChannel("https://api.github.com/repos/yt-dlp/yt-dlp-master-builds/releases/latest")

        companion object {
            @JvmField
            val _STABLE: STABLE = STABLE

            @JvmField
            val _NIGHTLY: NIGHTLY = NIGHTLY

            @JvmField
            val _MASTER: MASTER = MASTER
        }
    }


    const val baseName = "youtubedl-android"
    private const val packagesRoot = "packages"
    private const val pythonBinName = "libpython.so"
    private const val pythonLibName = "libpython.zip.so"
    private const val pythonDirName = "python"
    private const val ffmpegDirName = "ffmpeg"
    private const val ffmpegBinName = "libffmpeg.so"
    private const val ffprobeBinName = "libffprobe.so"
    private const val quickJsBinName = "libqjs.so"
    private const val aria2cDirName = "aria2c"
    const val ytdlpDirName = "yt-dlp"
    const val ytdlpBin = "yt-dlp"
    private const val pythonLibVersion = "pythonLibVersion"
    val objectMapper = ObjectMapper()

    @JvmStatic
    fun getInstance() = this
}