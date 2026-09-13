package dev.loki.android.core.ui

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PlaybackState(
    val playingMessageId: String? = null,
    val isPlaying: Boolean = false,
    val progress: Float = 0f // 0.0 to 1.0
)

class AudioPlaybackController(private val context: Context, private val coroutineScope: CoroutineScope) {
    private var exoPlayer: ExoPlayer? = null
    
    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()
    
    private var progressJob: Job? = null

    init {
        exoPlayer = ExoPlayer.Builder(context).build().apply {
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    _playbackState.value = _playbackState.value.copy(isPlaying = isPlaying)
                    if (isPlaying) {
                        startProgressPolling()
                    } else {
                        progressJob?.cancel()
                    }
                }
                
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) {
                        _playbackState.value = _playbackState.value.copy(isPlaying = false, progress = 1f)
                    }
                }
            })
        }
    }
    
    fun togglePlayPause(messageId: String, audioFilePath: String) {
        val player = exoPlayer ?: return
        val currentMsgId = _playbackState.value.playingMessageId
        
        if (currentMsgId == messageId) {
            if (player.isPlaying) {
                player.pause()
            } else {
                if (player.playbackState == Player.STATE_ENDED) {
                    player.seekTo(0)
                }
                player.play()
            }
        } else {
            // Stop current, load new
            player.stop()
            player.setMediaItem(MediaItem.fromUri("file://$audioFilePath"))
            player.prepare()
            player.play()
            _playbackState.value = PlaybackState(playingMessageId = messageId, isPlaying = true, progress = 0f)
        }
    }
    
    fun release() {
        exoPlayer?.release()
        exoPlayer = null
        progressJob?.cancel()
    }
    
    private fun startProgressPolling() {
        progressJob?.cancel()
        progressJob = coroutineScope.launch(Dispatchers.Main) {
            val player = exoPlayer ?: return@launch
            while (true) {
                if (player.isPlaying && player.duration > 0) {
                    val progress = (player.currentPosition.toFloat() / player.duration.toFloat()).coerceIn(0f, 1f)
                    _playbackState.value = _playbackState.value.copy(progress = progress)
                }
                delay(16) // ~60fps
            }
        }
    }
}
