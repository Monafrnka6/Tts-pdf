package com.example.ttspdf

data class ReaderSettings(
    val targetZoom: Float = 2.2f,
    val highlightMode: HighlightMode = HighlightMode.Word,
    val followMode: FollowMode = FollowMode.TTS,
    val wpm: Int = 170
)
enum class HighlightMode { Sentence, Word }
enum class FollowMode { TTS, AutoTimer }