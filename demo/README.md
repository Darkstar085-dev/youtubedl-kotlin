# yt-dlp Compose Demo

A minimal Jetpack Compose app that consumes the library modules directly, without Maven publishing.

## Run

1. Open the repository root in Android Studio.
2. Let Gradle sync, then select the `demo` run configuration.
3. Run on a physical Android device (API 30+ required).
4. Paste a video URL and choose **Get info** or **Download**.

The demo includes `:library`, `:ffmpeg`, and `:aria2c` as local Gradle project dependencies.
Downloads are written to the app-specific Downloads directory and are not automatically visible in
the public Downloads app.

## Notes

- Supported ABIs is `arm64-v8a`.
- Runtime initialization and yt-dlp work run off the main thread.
- Actual extraction/download behavior still needs to be verified on a device with a working build of
  the bundled native runtimes.
