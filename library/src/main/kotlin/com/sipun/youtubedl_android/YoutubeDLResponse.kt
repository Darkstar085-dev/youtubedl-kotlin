package com.sipun.youtubedl_android

class YoutubeDLResponse(
    val command: List<String?>,
    val exitCode: Int,
    val elapsedTime: Long,
    val out: String,
    val err: String
)