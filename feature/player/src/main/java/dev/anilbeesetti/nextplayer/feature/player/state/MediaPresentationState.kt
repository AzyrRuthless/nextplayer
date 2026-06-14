package dev.anilbeesetti.nextplayer.feature.player.state

import androidx.annotation.IntRange
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.media3.common.Player
import androidx.media3.common.listen
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.PlayerMessage
import dev.anilbeesetti.nextplayer.feature.player.extensions.formatted
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@UnstableApi
@Composable
fun rememberMediaPresentationState(player: Player): MediaPresentationState {
    val mediaPresentationState = remember { MediaPresentationState(player) }
    LaunchedEffect(player) { mediaPresentationState.observe() }
    return mediaPresentationState
}

@Stable
class MediaPresentationState(
    private val player: Player,
    @param:IntRange(from = 0) private val tickIntervalMs: Long = 500,
) {
    var position: Long by mutableLongStateOf(0L)
        private set

    var duration: Long by mutableLongStateOf(0L)
        private set

    var isPlaying: Boolean by mutableStateOf(false)
        private set

    var isLoading: Boolean by mutableStateOf(true)
        private set

    var isBuffering: Boolean by mutableStateOf(false)
        private set

    var abRepeatPointA: Long? by mutableStateOf(null)
        private set

    var abRepeatPointB: Long? by mutableStateOf(null)
        private set

    private var abRepeatMessage: PlayerMessage? = null

    fun toggleAbRepeat() {
        val pointA = abRepeatPointA
        if (pointA == null) {
            abRepeatPointA = position
        } else if (abRepeatPointB == null) {
            val currentPos = position
            if (currentPos > pointA) {
                abRepeatPointB = currentPos
                player.seekTo(pointA)
                scheduleAbRepeatMessage(pointA, currentPos)
            } else {
                resetAbRepeat()
            }
        } else {
            resetAbRepeat()
        }
    }

    fun resetAbRepeat() {
        abRepeatPointA = null
        abRepeatPointB = null
        cancelAbRepeatMessage()
    }

    private fun scheduleAbRepeatMessage(pointA: Long, pointB: Long) {
        val exoPlayer = (player as? ExoPlayer) ?: return
        cancelAbRepeatMessage()

        abRepeatMessage = exoPlayer.createMessage { _, _ ->
            val currentPointA = abRepeatPointA
            val currentPointB = abRepeatPointB
            if (currentPointA != null && currentPointB != null) {
                player.seekTo(currentPointA)
                updatePosition()
            }
        }.apply {
            setPosition(pointB)
            setDeleteAfterDelivery(false)
            setLooper(exoPlayer.applicationLooper)
            send()
        }
    }

    private fun cancelAbRepeatMessage() {
        abRepeatMessage?.cancel()
        abRepeatMessage = null
    }

    suspend fun observe() {
        updatePosition()
        updateDuration()
        isPlaying = player.isPlaying
        isLoading = player.isLoading
        isBuffering = player.playbackState == Player.STATE_BUFFERING

        coroutineScope {
            launch {
                player.listen { events ->
                    if (events.containsAny(
                            Player.EVENT_MEDIA_ITEM_TRANSITION,
                            Player.EVENT_TIMELINE_CHANGED,
                            Player.EVENT_PLAYBACK_STATE_CHANGED,
                        )
                    ) {
                        updateDuration()
                        if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) {
                            resetAbRepeat()
                        }
                    }

                    if (events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED)) {
                        this@MediaPresentationState.isBuffering = player.playbackState == Player.STATE_BUFFERING
                    }

                    if (events.contains(Player.EVENT_IS_PLAYING_CHANGED)) {
                        this@MediaPresentationState.isPlaying = player.isPlaying
                    }

                    if (events.contains(Player.EVENT_POSITION_DISCONTINUITY)) {
                        updatePosition()
                        val pointA = abRepeatPointA
                        val pointB = abRepeatPointB
                        if (pointA != null && pointB != null) {
                            if (position < (pointA - 200L) || position > pointB) {
                                resetAbRepeat()
                            }
                        }
                    }

                    if (events.containsAny(Player.EVENT_IS_LOADING_CHANGED)) {
                        this@MediaPresentationState.isLoading = player.isLoading
                    }
                }
            }

            while (true) {
                delay(tickIntervalMs)
                if (player.isPlaying) {
                    updatePosition()
                }
            }
        }
    }

    private fun updatePosition() {
        position = player.currentPosition.coerceAtLeast(0L)
    }

    private fun updateDuration() {
        duration = player.duration.coerceAtLeast(0L)
    }
}

val MediaPresentationState.positionFormatted: String
    get() = position.milliseconds.formatted()

val MediaPresentationState.durationFormatted: String
    get() = duration.milliseconds.formatted()

val MediaPresentationState.pendingPositionFormatted: String
    get() = (duration - position).milliseconds.formatted()
