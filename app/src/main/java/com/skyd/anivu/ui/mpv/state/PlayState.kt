package com.skyd.anivu.ui.mpv.state

data class PlayState(
    val isPlaying: Boolean,
    val isSeeking: Boolean,
    val currentPosition: Int,
    val duration: Int,
    val speed: Float,
    val title: String,
) {
    companion object {
        val initial = PlayState(
            isPlaying = false,
            isSeeking = false,
            currentPosition = 0,
            duration = 0,
            speed = 1f,
            title = "",
        )
    }
}