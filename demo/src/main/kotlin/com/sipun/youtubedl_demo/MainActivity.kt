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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        storagePermissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                if (granted) initializeLibrary() else setStatus("Storage permission is required on Android 9 and below to save downloads.")
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
                        onDownload = { url, selectedFormat, progress, done, failed ->
                            lifecycleScope.launch {
                                var staging: File? = null
                                try {
                                    staging = withContext(Dispatchers.IO) {
                                        File(cacheDir, "yt-dlp-downloads").apply {
                                            if (exists()) deleteRecursively()
                                            if (!mkdirs()) error("Could not create temporary download folder")
                                        }
                                    }
                                    val formatOption = selectedFormat?.let { format ->
                                        val id = format.formatId
                                        if (id.isNullOrBlank()) "bestvideo*+bestaudio/best"
                                        else if (format.acodec.isNullOrBlank() || format.acodec == "none") "$id+bestaudio/best"
                                        else id
                                    } ?: "bestvideo*+bestaudio/best"
                                    val request = YoutubeDLRequest(url)
                                        .addOption(
                                            "-o",
                                            File(staging, "%(title)s.%(ext)s").absolutePath
                                        )
                                        .addOption("-f", formatOption)
                                        .addOption("--merge-output-format", "mp4")
                                        .addOption(
                                            "--external-downloader",
                                            File(
                                                applicationInfo.nativeLibraryDir,
                                                "libaria2c.so"
                                            ).absolutePath
                                        )
                                    withContext(Dispatchers.IO) {
                                        YoutubeDL.execute(request) { value, eta, line ->
                                            mainHandler.post { progress(value, eta, line) }
                                        }
                                    }
                                    val splitStreamPattern = Regex(
                                        """.+\.f\d+\.(mp4|webm|mkv|m4a|opus|aac)$""",
                                        RegexOption.IGNORE_CASE
                                    )
                                    val downloadedFiles =
                                        staging!!.listFiles()?.filter { it.isFile }.orEmpty()
                                    if (downloadedFiles.any { splitStreamPattern.matches(it.name) }) {
                                        throw IllegalStateException(
                                            "FFmpeg did not merge the selected audio and video streams. " +
                                                    "Separate streams were kept out of Downloads."
                                        )
                                    }
                                    val destination =
                                        withContext(Dispatchers.IO) { publishDownloads(staging!!) }
                                    done(destination)
                                } catch (e: Exception) {
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
                        if (src.extension.equals(
                                "mp4",
                                true
                            )
                        ) "video/mp4" else "application/octet-stream"
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
    onDownload: (String, VideoFormat?, (Float, Long, String) -> Unit, (String) -> Unit, (String) -> Unit) -> Unit
) {
    var url by remember { mutableStateOf("") }
    var ready by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf("Checking storage access and initializing yt-dlp…") }
    var info by remember { mutableStateOf<VideoInfo?>(null) }
    var selectedId by remember { mutableStateOf<String?>(null) }
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

    val choices = info?.formats.orEmpty()
        .filter { it.formatId != null && (it.height > 0 || !it.formatNote.isNullOrBlank()) }
        .distinctBy { it.formatId }
        .sortedByDescending { it.height }
        .take(12)
        .map { f ->
            val quality = if (f.height > 0) "${f.height}p" else f.formatNote ?: "Format"
            val codec = f.ext?.uppercase(Locale.getDefault()) ?: "Media"
            val type =
                if (f.acodec.isNullOrBlank() || f.acodec == "none") "video only" else "video + audio"
            val bytes = if (f.fileSize > 0) f.fileSize else f.fileSizeApproximate
            FormatChoice(
                f,
                "$quality ($type)",
                "$codec · ${f.width}×${f.height}",
                formatBytes(bytes)
            )
        }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "yt-dlp Demo",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFF7F4FF)
            )
            Spacer(Modifier.weight(1f))
            Text("⋮", fontSize = 28.sp, color = Muted)
        }
        Card(
            colors = CardDefaults.cardColors(containerColor = Panel),
            shape = RoundedCornerShape(18.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, PanelBorder)
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("VIDEO URL", fontSize = 12.sp, color = Muted)
                OutlinedTextField(
                    value = url, onValueChange = { url = it; savedTo = null },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Paste video link here", color = Muted) },
                    singleLine = true, enabled = ready && !busy,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Purple, unfocusedBorderColor = Color(0xFF565866),
                        focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                        cursorColor = Purple
                    )
                )
                Button(
                    onClick = {
                        busy = true; status = "Fetching video information…"; info =
                        null; selectedId = null; savedTo = null
                        onInfo(url.trim(), { result ->
                            info = result
                            selectedId =
                                result.formats.orEmpty().firstOrNull { it.height == 720 }?.formatId
                                    ?: result.formats.orEmpty().filter { it.height > 0 }
                                        .maxByOrNull { it.height }?.formatId
                            status = "Video information loaded"; busy = false
                        }, { status = "Metadata failed: $it"; busy = false })
                    },
                    enabled = ready && !busy && url.trim().startsWith("http"),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Purple,
                        contentColor = Color(0xFF181321)
                    ),
                    shape = RoundedCornerShape(30.dp)
                ) { Text("⌕    Get Info", fontWeight = FontWeight.Bold) }
                Button(
                    onClick = {
                        busy = true; status = "Downloading…"; progress = 0f; logLine = ""; savedTo =
                        null
                        val selected =
                            choices.firstOrNull { it.format.formatId == selectedId }?.format
                        onDownload(url.trim(), selected, { value, remaining, line ->
                            progress = (value / 100f).coerceIn(0f, 1f); eta = remaining; logLine =
                            line.take(300)
                        }, { path ->
                            progress = 1f; savedTo = path; status = "Download completed"; busy =
                            false
                        }, { status = "Download failed: $it"; busy = false })
                    },
                    enabled = ready && !busy && info != null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (ready && !busy && info != null) Purple else Color(
                            0xFF343540
                        ),
                        contentColor = if (ready && !busy && info != null) Color(0xFF181321) else Color(
                            0xFF858695
                        )
                    ),
                    shape = RoundedCornerShape(30.dp)
                ) { Text("⬇    Download", fontWeight = FontWeight.Bold) }
            }
        }

        if (busy) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CircularProgressIndicator(Modifier.size(22.dp), color = Purple, strokeWidth = 2.dp)
                Text(status, color = Muted, fontSize = 13.sp)
            }
        } else if (info == null || savedTo == null) {
            Text(
                status,
                color = if (status.startsWith("Initialization failed") || status.contains("failed")) Color(
                    0xFFFF9292
                ) else Muted,
                fontSize = 13.sp
            )
        }

        info?.let { video ->
            Card(
                colors = CardDefaults.cardColors(containerColor = Panel),
                shape = RoundedCornerShape(18.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, PanelBorder)
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "VIDEO INFORMATION",
                        fontSize = 12.sp,
                        color = Purple,
                        fontWeight = FontWeight.Bold
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        AsyncImage(
                            model = video.thumbnail, contentDescription = "Video thumbnail",
                            modifier = Modifier
                                .size(width = 118.dp, height = 88.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF292B36)),
                            contentScale = ContentScale.Crop
                        )
                        Column(
                            Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Text(
                                video.title ?: video.fulltitle ?: "Video",
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp
                            )
                            Text(
                                "♟  ${video.uploader ?: video.extractor ?: "Unknown creator"}",
                                color = Muted,
                                fontSize = 12.sp
                            )
                            Text(
                                "◷  ${formatDuration(video.duration)}",
                                color = Muted,
                                fontSize = 12.sp
                            )
                            if (!video.viewCount.isNullOrBlank()) Text(
                                "◉  ${video.viewCount} views",
                                color = Muted,
                                fontSize = 12.sp
                            )
                        }
                    }
                    Text(
                        "AVAILABLE FORMATS",
                        fontSize = 12.sp,
                        color = Purple,
                        fontWeight = FontWeight.Bold
                    )
                    if (choices.isEmpty()) {
                        Text(
                            "Format details are unavailable; best quality will be selected automatically.",
                            color = Muted,
                            fontSize = 12.sp
                        )
                    } else {
                        choices.forEach { choice ->
                            val selected = selectedId == choice.format.formatId
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(
                                        if (selected) Color(0xFF29253B) else Color(
                                            0xFF20222B
                                        )
                                    )
                                    .clickable(enabled = !busy) {
                                        selectedId = choice.format.formatId
                                    }
                                    .padding(11.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(
                                    if (selected) "◉" else "○",
                                    color = if (selected) Purple else Muted,
                                    fontSize = 20.sp
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        choice.label,
                                        color = Color.White,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(choice.detail, color = Muted, fontSize = 11.sp)
                                }
                                Text(choice.size, color = Muted, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }

        if (progress > 0f && savedTo == null) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Panel),
                shape = RoundedCornerShape(18.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, PanelBorder)
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "⬇  Downloading…",
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.weight(1f))
                        Text("${progress.toInt()}%", color = Color.White, fontSize = 12.sp)
                    }
                    LinearProgressIndicator(
                        progress = progress,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(7.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        color = Purple,
                        trackColor = Color(0xFF393B49)
                    )
                    Row(Modifier.fillMaxWidth()) {
                        Text(
                            if (eta > 0) "ETA ${eta}s" else "Working…",
                            color = Muted,
                            fontSize = 12.sp
                        )
                        Spacer(Modifier.weight(1f))
                        Text("yt-dlp runtime", color = Muted, fontSize = 12.sp)
                    }
                    if (logLine.isNotBlank()) {
                        Text(
                            logLine,
                            color = Muted,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            modifier = Modifier
                                .background(
                                    Color(0xFF13141B),
                                    RoundedCornerShape(10.dp)
                                )
                                .padding(10.dp)
                        )
                    }
                }
            }
        }

        savedTo?.let { path ->
            Card(
                colors = CardDefaults.cardColors(containerColor = Panel),
                shape = RoundedCornerShape(18.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, PanelBorder)
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "✓  Download Completed",
                        color = Green,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                    LinearProgressIndicator(
                    progress = { 1f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(7.dp)
                                                .clip(RoundedCornerShape(8.dp)),
                    color = Purple,
                    trackColor = Color(0xFF393B49),
                    strokeCap = COMPILED_CODE,
                    )
                    Text("File saved to:", color = Muted, fontSize = 12.sp)
                    Text("$path", color = Color.White, fontSize = 13.sp)
                    Text(
                        "Open your device's Downloads app to find the file.",
                        color = Muted,
                        fontSize = 12.sp
                    )
                    Button(
                        onClick = {
                            url = ""; info = null; selectedId = null; savedTo = null; progress =
                            0f; status = "Ready to test"
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Purple,
                            contentColor = Color(0xFF181321)
                        ),
                        shape = RoundedCornerShape(28.dp)
                    ) {
                        Text("↻    Download Another")
                    }
                }
            }
        }

        Text(
            "Files are saved to Downloads/yt-dlp Demo. Android 10+ uses scoped storage.",
            color = Color(0xFF898B9A),
            fontSize = 11.sp
        )
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
