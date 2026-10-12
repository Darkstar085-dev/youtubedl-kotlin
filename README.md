# youtubedl-kotlin

A Kotlin-first Android library for running [yt-dlp](https://github.com/yt-dlp/yt-dlp) on Android. It
bundles the Python runtime and provides optional FFmpeg and aria2 support.

## Overview

- **Language:** Kotlin
- **Minimum Android version:** Android 11 (API 30)
- **Supported ABI:** `arm64-v8a` only
- **Build requirements:** JDK 17 and Android SDK
- **License:** GPL-3.0; see [LICENSE](LICENSE)

## Modules

| Module     | Purpose                                                                                     |
|------------|---------------------------------------------------------------------------------------------|
| `:library` | Core yt-dlp integration, Python runtime, request execution, and media information           |
| `:ffmpeg`  | Optional FFmpeg/ffprobe runtime for media processing, merging, and extraction               |
| `:aria2c`  | Optional aria2 runtime for external downloads                                               |
| `:demo`    | Jetpack Compose app for testing metadata extraction and downloads against the local modules |

The core `:library` module is the starting point. Include `:ffmpeg` or `:aria2c` only when your app
needs those features.

## Getting started

### 1. Add the project

Include this repository as a Gradle project or add its modules to your Android project. The module
names are `:library`, `:ffmpeg`, and `:aria2c`.

### 2. Initialize the core library

Call initialization with your application context before requesting media information or starting a
download. For example, from your `Application.onCreate()`:

```kotlin
import android.app.Application
import com.sipun.youtubedl_android.YoutubeDL

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        YoutubeDL.init(this)
    }
}
```

### 3. Read media information

```kotlin
import com.sipun.youtubedl_android.YoutubeDL

val info = YoutubeDL.getInfo(url)
val title = info.title
```

### 4. Run a download

```kotlin
import com.sipun.youtubedl_android.YoutubeDL
import com.sipun.youtubedl_android.YoutubeDLRequest

val request = YoutubeDLRequest(url)
    .addOption("-o", outputFile.absolutePath)

val response = YoutubeDL.execute(request)
```

Run initialization, metadata requests, and downloads off the main thread. Handle
`YoutubeDLException`, interruption, and cancellation in your app.

### Optional runtimes

Initialize the optional runtime from your application context when your app uses it:

```kotlin
import com.sipun.ffmpeg.FFmpeg

FFmpeg.init(this)
```

```kotlin
import com.sipun.aria2c.Aria2c

Aria2c.init(this)
```

For aria2 downloads, pass yt-dlp's external-downloader options in the request as required by your
use case.

## Compose demo

The repository includes a small Jetpack Compose app to test the library without Maven publishing.
Open the repository root in Android Studio, sync Gradle, and run the `demo` configuration on a
physical device. Paste a video URL to test metadata extraction or download.
See [demo/README.md](demo/README.md) for notes.

## Build and test

Run these commands from the repository root:

```bash
# Run core library unit tests
./gradlew :library:testDebugUnitTest

# Build all library modules and compile the Compose demo
./gradlew :library:assembleRelease :ffmpeg:assembleRelease :aria2c:assembleRelease :demo:assembleDebug
```

## Bundled binary sizes

Sizes below are the current on-disk sizes of the bundled runtime files in this repository. They are
not the compressed download size of a Git clone. Values are generated from the tracked files when
the **Update Runtimes** workflow is manually dispatched.

<!-- RUNTIME-SIZES:START -->

| ABI       | Component              |     File size |
|-----------|------------------------|--------------:|
| all       | yt-dlp                 |       3.07 MB |
| arm64-v8a | FFmpeg                 | 399,016 bytes |
| arm64-v8a | FFmpeg archive         |      41.77 MB |
| arm64-v8a | Python runtime archive |      24.21 MB |
| arm64-v8a | Python shared library  |   4,448 bytes |
| arm64-v8a | QuickJS                | 129,552 bytes |
| arm64-v8a | aria2                  |       2.55 MB |
| arm64-v8a | aria2 archive          |       4.64 MB |
| arm64-v8a | ffprobe                | 193,584 bytes |

<!-- RUNTIME-SIZES:END -->

The full file-by-file byte counts are maintained in [runtime-sizes.tsv](runtime-sizes.tsv).

## Project background and changes

This project is derived
from [yausername/youtubedl-android](https://github.com/yausername/youtubedl-android), with
repository-level changes for this Kotlin-focused project.

- **Source lineage:** The project is still derived from the upstream codebase; its independent
  commit history does not mean the source was written from scratch.
- **Project identity:** The Gradle root project is named `youtubedl-kotlin`.
- **Packages and namespaces:** Code has been moved to the `com.sipun.*` namespace where updated,
  replacing the original `com.yausername.*` namespace in those areas.
- **Modules:** The project is organized around `:library`, with optional `:ffmpeg` and `:aria2c`
  modules and a local Compose demo.
- **Supported architectures:** Bundled native runtimes target `arm64-v8a` only; other ABIs are not
  included.

The changes listed here describe the current repository setup; they do not imply that every upstream
implementation detail has been rewritten.
