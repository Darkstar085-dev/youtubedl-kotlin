package com.sipun.youtubedl_demo

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import coil3.compose.AsyncImage
import com.sipun.aria2c.Aria2c
import com.sipun.ffmpeg.FFmpeg
import com.sipun.youtubedl_android.YoutubeDL
import com.sipun.youtubedl_android.YoutubeDLRequest
import com.sipun.youtubedl_android.mapper.VideoFormat
import com.sipun.youtubedl_android.mapper.VideoInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Locale

private val Bg = Color(0xFF101116)
private val Panel = Color(0xFF1B1D26)
private val PanelBorder = Color(0xFF30323E)
private val Purple = Color(0xFFB29AFF)
private val Muted = Color(0xFFB9BBC9)
private val Green = Color(0xFF69D98A)

class MainActivity : ComponentActivity() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var storagePermissionLauncher: ActivityResultLauncher<String>
    private lateinit var notificationPermissionLauncher: ActivityResultLauncher<String>
    private val notificationManager by lazy { getSystemService(android.app.NotificationManager::class.java) }
    private val notificationId = 4107
    private val notificationChannelId = "download_status"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        storagePermissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                if (granted) initializeLibrary() else setStatus("Storage permission is required on Android 9 and below to save downloads.")
            }
        notificationPermissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* Downloads remain usable if notifications are denied. */ }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(
                android.app.NotificationChannel(
                    notificationChannelId,
                    "Download status",
                    android.app.NotificationManager.IMPORTANCE_LOW
                ).apply { description = "Shows active and completed video/audio downloads" }
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Purple, onPrimary = Color(0xFF191322), background = Bg,
                    surface = Panel, onSurface = Color(0xFFF3F1FA), secondary = Purple
                )
            ) {
                Surface(Modifier.fillMaxSize(), color = Bg) {
                    DemoScreen(
                        onInitialize = { onReady, onError ->
                            readyCallback = onReady
                            errorCallback = onError
                            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                                ContextCompat.checkSelfPermission(
                                    this,
                                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                                ) != PackageManager.PERMISSION_GRANTED
                            ) storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                            else initializeLibrary()
                        },
                        onInfo = { url, done, failed ->
                            lifecycleScope.launch {
                                try {
                                    val info =
                                        withContext(Dispatchers.IO) { YoutubeDL.getInfo(url) }
                                    done(info)
                                } catch (e: Exception) {
                                    failed(e.message ?: "Could not fetch video information")
                                }
                            }
                        },
                        onDownload = { url, selectedFormat, audioOnly, dataBudgetMb, progress, done, failed ->
                            showDownloadNotification("Preparing download", "Starting download", 0, true)
                            lifecycleScope.launch {
                                var staging: File? = null
                                try {
                                    staging = withContext(Dispatchers.IO) {
                                        File(cacheDir, "yt-dlp-downloads").apply {
                                            if (exists()) deleteRecursively()
                                            if (!mkdirs()) error("Could not create temporary download folder")
                                        }
                                    }
                                    val request = YoutubeDLRequest(url)
                                        .addOption("-o", File(staging, "%(title)s.%(ext)s").absolutePath)
                                    if (audioOnly) {
                                        request.addOption("-f", selectedFormat?.formatId ?: "bestaudio/best")
                                            .addOption("-x")
                                            .addOption("--audio-format", "mp3")
                                            .addOption("--audio-quality", "0")
                                    } else {
                                        val budgetBytes = dataBudgetMb * 1024L * 1024L
                                        val formatOption = selectedFormat?.let { format ->
                                            val id = format.formatId
                                            if (id.isNullOrBlank()) "bestvideo*+bestaudio/best"
                                            else if (format.acodec.isNullOrBlank() || format.acodec == "none") "$id+bestaudio/best"
                                            else id
                                        } ?: "bestvideo*[filesize_approx<=${budgetBytes}]+bestaudio/best[filesize_approx<=${budgetBytes}]/bestvideo*+bestaudio/best"
                                        request.addOption("-f", formatOption)
                                            .addOption("--merge-output-format", "mp4")
                                    }
                                    request.addOption(
                                        "--external-downloader",
                                        File(applicationInfo.nativeLibraryDir, "libaria2c.so").absolutePath
                                    )
                                    withContext(Dispatchers.IO) {
                                        YoutubeDL.execute(request) { value, eta, line ->
                                            mainHandler.post {
                                                progress(value, eta, line)
                                                showDownloadNotification(
                                                    if (audioOnly) "Downloading MP3" else "Downloading video",
                                                    line.take(100).ifBlank { "Download in progress" },
                                                    value.toInt().coerceIn(0, 100),
                                                    true
                                                )
                                            }
                                        }
                                    }
                                    val splitStreamPattern = Regex(
                                        """.+\.f\d+\.(mp4|webm|mkv|m4a|opus|aac)$""",
                                        RegexOption.IGNORE_CASE
                                    )
                                    val downloadedFiles = staging.listFiles()?.filter { it.isFile }.orEmpty()
                                    if (downloadedFiles.any { splitStreamPattern.matches(it.name) }) {
                                        throw IllegalStateException("FFmpeg did not merge the selected audio and video streams.")
                                    }
                                    if (audioOnly && downloadedFiles.none { it.extension.equals("mp3", true) }) {
                                        throw IllegalStateException("MP3 conversion did not produce an MP3 file.")
                                    }
                                    val destination = withContext(Dispatchers.IO) { publishDownloads(staging) }
                                    showDownloadNotification("Download complete", "Saved to Downloads/yt-dlp Demo", 100, false)
                                    done(destination)
                                } catch (e: Exception) {
                                    showDownloadNotification("Download failed", e.message ?: "Please try again", 0, false)
                                    failed(e.message ?: "Download failed")
                                } finally {
                                    staging?.deleteRecursively()
                                }
                            }
                        }
                    )
                }
            }
        }
    }

    private var readyCallback: (() -> Unit)? = null
    private var errorCallback: ((String) -> Unit)? = null

    private fun setStatus(message: String) {
        errorCallback?.invoke(message)
    }

    private fun showDownloadNotification(title: String, message: String, progress: Int, ongoing: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.app.Notification.Builder(this, notificationChannelId)
        } else {
            @Suppress("DEPRECATION")
            android.app.Notification.Builder(this)
        }
        builder
            .setSmallIcon(if (ongoing) android.R.drawable.stat_sys_download else android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(message)
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
        if (ongoing) builder.setProgress(100, progress.coerceIn(0, 100), progress <= 0)
        else builder.setProgress(0, 0, false)
        notificationManager.notify(notificationId, builder.build())
    }

    private fun initializeLibrary() {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    FFmpeg.init(applicationContext)
                    Aria2c.init(applicationContext)
                    YoutubeDL.init(applicationContext)
                }
                readyCallback?.invoke()
            } catch (e: Exception) {
                errorCallback?.invoke(e.message ?: "Library initialization failed")
            }
        }
    }

    private fun publishDownloads(staging: File): String {
        val files = staging.listFiles()?.filter { it.isFile && it.length() > 0L }.orEmpty()
        if (files.isEmpty()) error("yt-dlp finished but no output file was found")
        val relative = Environment.DIRECTORY_DOWNLOADS + "/yt-dlp Demo"
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val folder = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "yt-dlp Demo"
            )
            if (!folder.exists() && !folder.mkdirs()) error("Could not create Downloads/yt-dlp Demo")
            files.forEach { src ->
                FileInputStream(src).use { input ->
                    FileOutputStream(
                        File(
                            folder,
                            src.name
                        )
                    ).use { output -> input.copyTo(output) }
                }
            }
        } else {
            files.forEach { src ->
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, src.name)
                    put(
                        MediaStore.Downloads.MIME_TYPE,
                        when (src.extension.lowercase(Locale.ROOT)) { "mp4" -> "video/mp4"; "mp3" -> "audio/mpeg"; "m4a" -> "audio/mp4"; else -> "application/octet-stream" }
                    )
                    put(MediaStore.Downloads.RELATIVE_PATH, relative)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: error("Could not create destination file")
                try {
                    contentResolver.openOutputStream(uri)
                        ?.use { out -> FileInputStream(src).use { it.copyTo(out) } }
                        ?: error("Could not open destination file")
                    contentResolver.update(
                        uri,
                        ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                        null,
                        null
                    )
                } catch (e: Exception) {
                    contentResolver.delete(uri, null, null); throw e
                }
            }
        }
        return "Downloads/yt-dlp Demo"
    }
}


private data class FormatChoice(
    val format: VideoFormat,
    val label: String,
    val detail: String,
    val size: String
)

@Composable
private fun DemoScreen(
    onInitialize: ((() -> Unit), (String) -> Unit) -> Unit,
    onInfo: (String, (VideoInfo) -> Unit, (String) -> Unit) -> Unit,
    onDownload: (String, VideoFormat?, Boolean, Long, (Float, Long, String) -> Unit, (String) -> Unit, (String) -> Unit) -> Unit
) {
    var url by remember { mutableStateOf("") }
    var ready by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf("Initializing yt-dlp…") }
    var info by remember { mutableStateOf<VideoInfo?>(null) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var audioTab by remember { mutableStateOf(false) }
    var dataBudgetMb by remember { mutableStateOf("150") }
    var progress by remember { mutableStateOf(0f) }
    var eta by remember { mutableStateOf(0L) }
    var logLine by remember { mutableStateOf("") }
    var savedTo by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        onInitialize(
            { ready = true; busy = false; status = "Ready to test" },
            { busy = false; status = "Initialization failed: $it" }
        )
    }

    val rawFormats = info?.formats.orEmpty().filter { !it.formatId.isNullOrBlank() }
    val videoChoices = rawFormats
        .filter { it.height > 0 && !it.vcodec.isNullOrBlank() && it.vcodec != "none" }
        .groupBy { it.height }
        .mapNotNull { (_, formats) ->
            val chosen = formats.sortedWith(
                compareByDescending<VideoFormat> { it.ext.equals("mp4", true) }
                    .thenByDescending { !it.acodec.isNullOrBlank() && it.acodec != "none" }
                    .thenByDescending { if (it.fileSize > 0) it.fileSize else it.fileSizeApproximate }
            ).firstOrNull() ?: return@mapNotNull null
            val bytes = if (chosen.fileSize > 0) chosen.fileSize else chosen.fileSizeApproximate
            FormatChoice(
                chosen,
                "${chosen.height}p",
                "",
                formatBytes(bytes)
            )
        }
        .sortedByDescending { it.format.height }

    val audioChoices = rawFormats
        .filter { (it.vcodec.isNullOrBlank() || it.vcodec == "none") && !it.acodec.isNullOrBlank() && it.acodec != "none" }
        .groupBy { Pair(it.abr, it.ext?.lowercase(Locale.ROOT)) }
        .mapNotNull { (_, formats) ->
            val chosen = formats.maxByOrNull { if (it.fileSize > 0) it.fileSize else it.fileSizeApproximate }
                ?: return@mapNotNull null
            val bytes = if (chosen.fileSize > 0) chosen.fileSize else chosen.fileSizeApproximate
            FormatChoice(
                chosen,
                if (chosen.abr > 0) "${chosen.abr} kbps" else "Audio quality",
                "",
                formatBytes(bytes)
            )
        }
        .sortedByDescending { it.format.abr }

    val choices = if (audioTab) audioChoices else videoChoices

    Box(Modifier.fillMaxSize().background(Bg)) {
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = if (info != null) 112.dp else 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "↓",
                modifier = Modifier.background(Purple, RoundedCornerShape(50)).padding(horizontal = 15.dp, vertical = 8.dp),
                color = Color(0xFF191322), fontSize = 22.sp, fontWeight = FontWeight.Bold
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("yt-dlp Demo", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color(0xFFF7F4FF))
                Text("Smart video & audio downloader", color = Muted, fontSize = 12.sp)
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = Panel),
            shape = RoundedCornerShape(22.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, PanelBorder)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("VIDEO URL", fontSize = 13.sp, color = Purple, fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it; savedTo = null },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Paste a video link here", color = Muted) },
                    singleLine = true,
                    enabled = ready && !busy,
                    shape = RoundedCornerShape(15.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Purple, unfocusedBorderColor = Color(0xFF454856),
                        focusedTextColor = Color.White, unfocusedTextColor = Color.White, cursorColor = Purple
                    )
                )
                Button(
                    onClick = {
                        busy = true; status = "Fetching available formats…"; info = null
                        selectedId = null; savedTo = null
                        onInfo(url.trim(), { result ->
                            info = result
                            selectedId = null
                            status = "Formats loaded"; busy = false
                        }, { status = "Metadata failed: $it"; busy = false })
                    },
                    enabled = ready && !busy && url.trim().startsWith("http"),
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF302A4A), contentColor = Purple),
                    shape = RoundedCornerShape(28.dp)
                ) { Text("Get video & audio options", fontWeight = FontWeight.Bold) }
            }
        }

        info?.let { video ->
            Card(
                colors = CardDefaults.cardColors(containerColor = Panel),
                shape = RoundedCornerShape(18.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, PanelBorder)
            ) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        AsyncImage(
                            model = video.thumbnail,
                            contentDescription = video.title,
                            modifier = Modifier.size(width = 104.dp, height = 78.dp).clip(RoundedCornerShape(10.dp)),
                            contentScale = ContentScale.Crop
                        )
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(video.title ?: video.fulltitle ?: "Video ready", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 3)
                            Text(video.uploader ?: video.extractor ?: "Video source", color = Muted, fontSize = 11.sp, maxLines = 1)
                            Text("${formatDuration(video.duration)} · ${video.viewCount?.let { count -> runCatching { String.format(Locale.getDefault(), "%,d views", count.toLong()) }.getOrNull() } ?: "Views unavailable"}", color = Muted, fontSize = 10.sp)
                        }
                    }
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(18.dp)).padding(5.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf(false to "Video", true to "Audio · MP3").forEach { (isAudio, label) ->
                val selected = audioTab == isAudio
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(14.dp))
                        .background(if (selected) Color(0xFF39304F) else Color.Transparent)
                        .clickable(enabled = !busy) { audioTab = isAudio; selectedId = null }
                        .padding(vertical = 13.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(label, color = if (selected) Purple else Muted, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        if (!audioTab) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Panel),
                shape = RoundedCornerShape(18.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, PanelBorder)
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("SMART DATA DOWNLOAD", color = Purple, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text("Choose the largest suitable quality using estimated size. The budget is a target, not a guaranteed cap when source sizes are unknown.", color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
                    OutlinedTextField(
                        value = dataBudgetMb,
                        onValueChange = { value -> dataBudgetMb = value.filter(Char::isDigit).take(5) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Preferred data budget (MB)") },
                        singleLine = true,
                        enabled = !busy,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Purple, unfocusedBorderColor = Color(0xFF454856),
                            focusedTextColor = Color.White, unfocusedTextColor = Color.White
                        )
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(50L, 150L, 300L).forEach { preset ->
                            val active = (dataBudgetMb.toLongOrNull() ?: 150L) == preset
                            Button(
                                onClick = { dataBudgetMb = preset.toString() },
                                modifier = Modifier.weight(1f).height(38.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (active) Color(0xFF39304F) else Color(0xFF242630),
                                    contentColor = if (active) Purple else Muted
                                ),
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp)
                            ) { Text("$preset MB", fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
                        }
                        val unlimited = dataBudgetMb == "100000"
                        Button(
                            onClick = { dataBudgetMb = "100000" },
                            modifier = Modifier.weight(1f).height(38.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (unlimited) Color(0xFF39304F) else Color(0xFF242630),
                                contentColor = if (unlimited) Purple else Muted
                            ),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp)
                        ) { Text("No limit", fontSize = 11.sp) }
                    }
                }
            }
        }

        if (info != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (audioTab) "AUDIO VARIANTS" else "VIDEO QUALITIES", color = Purple, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Spacer(Modifier.weight(1f))
                Text("${choices.size} options", color = Muted, fontSize = 12.sp)
            }

            if (!audioTab) {
                val selected = selectedId == null
                FormatRow(
                    title = "Smart · Auto select",
                    detail = "Prefer the highest quality within your budget",
                    size = "Recommended",
                    selected = selected,
                    enabled = !busy,
                    onClick = { selectedId = null }
                )
            }

            if (choices.isEmpty()) {
                Text(
                    if (audioTab) "No separate audio variants were reported. Try Auto download to let yt-dlp choose an available audio stream."
                    else "No video variants were reported. Smart Auto download can still ask yt-dlp for the best available video.",
                    color = Muted, fontSize = 13.sp, lineHeight = 18.sp
                )
            } else {
                choices.forEach { choice ->
                    val selected = selectedId == choice.format.formatId
                    FormatRow(
                        title = choice.label,
                        detail = choice.detail,
                        size = choice.size,
                        selected = selected,
                        enabled = !busy,
                        onClick = { selectedId = choice.format.formatId }
                    )
                }
            }

        }

        if (busy && !ready) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Purple, strokeWidth = 2.dp)
                Text(status, color = Muted, fontSize = 12.sp)
            }
        } else if (status.contains("failed", ignoreCase = true) || status.contains("error", ignoreCase = true)) {
            Text(status, color = Color(0xFFFF9D9D), fontSize = 12.sp)
        }

        if (progress > 0f && savedTo == null) {
            Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(status, color = Color.White, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.weight(1f))
                        Text("${progress.toInt()}%", color = Color.White, fontSize = 12.sp)
                    }
                    LinearProgressIndicator(
                        progress = { progress }, modifier = Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(8.dp)),
                        color = Purple, trackColor = Color(0xFF393B49)
                    )
                    Text(if (eta > 0) "ETA ${eta}s" else "Working…", color = Muted, fontSize = 12.sp)
                    if (logLine.isNotBlank()) Text(logLine, color = Muted, fontSize = 11.sp)
                }
            }
        }

        savedTo?.let { path ->
            Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Download completed", color = Green, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text("Saved to: $path", color = Color.White, fontSize = 13.sp)
                    Button(
                        onClick = { url = ""; info = null; selectedId = null; savedTo = null; progress = 0f; status = "Ready to test" },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Purple, contentColor = Color(0xFF181321)),
                        shape = RoundedCornerShape(28.dp)
                    ) { Text("Download another") }
                }
            }
        }
        Text("Saved to Downloads/yt-dlp Demo. File-size estimates depend on source metadata.", color = Color(0xFF898B9A), fontSize = 11.sp)
    }

    if (info != null) {
        Surface(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding(),
            color = Bg,
            shadowElevation = 10.dp
        ) {
            val selectedChoice = choices.firstOrNull { it.format.formatId == selectedId }
            val budget = (dataBudgetMb.toLongOrNull() ?: 150L).coerceIn(1L, 100000L)
            val smartChoice = videoChoices.filter { choice ->
                val bytes = if (choice.format.fileSize > 0) choice.format.fileSize else choice.format.fileSizeApproximate
                bytes in 1..(budget * 1024L * 1024L)
            }.maxByOrNull { it.format.height }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        if (audioTab) selectedChoice?.label ?: "Best available audio"
                        else selectedChoice?.label ?: smartChoice?.label ?: "Smart quality",
                        color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1
                    )
                    Text(
                        if (audioTab) "MP3 · ${selectedChoice?.size ?: "Size varies"}"
                        else "MP4 · ${selectedChoice?.size ?: smartChoice?.size ?: "Size varies"}",
                        color = Purple, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1
                    )
                }
                Button(
                    onClick = {
                        busy = true
                        status = if (audioTab) "Downloading audio and converting to MP3…" else "Downloading video…"
                        progress = 0f; logLine = ""; savedTo = null
                        val selected = choices.firstOrNull { it.format.formatId == selectedId }?.format
                        onDownload(url.trim(), selected, audioTab, budget, { value, remaining, line ->
                            progress = (value / 100f).coerceIn(0f, 1f); eta = remaining; logLine = line.take(300)
                        }, { path ->
                            progress = 1f; savedTo = path; status = "Download completed"; busy = false
                        }, { status = "Download failed: $it"; busy = false })
                    },
                    enabled = ready && !busy && info != null,
                    modifier = Modifier.weight(1.2f).height(50.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Purple, contentColor = Color(0xFF191322), disabledContainerColor = Color(0xFF343540), disabledContentColor = Color(0xFF858695)),
                    shape = RoundedCornerShape(28.dp)
                ) { Text(if (audioTab) "↓  Download MP3" else if (selectedId == null) "↓  Smart Download" else "↓  Download video", fontWeight = FontWeight.Bold, fontSize = 12.sp) }
            }
        }
    }

}

}

@Composable
private fun FormatRow(
    title: String,
    detail: String,
    size: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(if (selected) Color(0xFF29253B) else Color(0xFF20222B))
            .then(
                if (selected) Modifier.background(Color.Transparent, RoundedCornerShape(14.dp))
                else Modifier
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(if (selected) "◉" else "○", color = if (selected) Purple else Muted, fontSize = 19.sp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            if (detail.isNotBlank()) {
                Text(detail, color = Muted, fontSize = 10.sp, maxLines = 1)
            }
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(size, color = if (selected) Purple else Muted, fontSize = 11.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
            if (selected) {
                Text("SELECTED", color = Purple, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
            }
        }
    }
}

private fun formatDuration(seconds: Int): String {
    if (seconds <= 0) return "Duration unavailable"
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60
    return if (hours > 0) String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, secs)
    else String.format(Locale.getDefault(), "%d:%02d", minutes, secs)
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "Size unknown"
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1.0) String.format(Locale.getDefault(), "~%.0f MB", mb)
    else String.format(Locale.getDefault(), "~%.0f KB", bytes / 1024.0)
}
