package dev.anilbeesetti.nextplayer.feature.player.buttons

import androidx.annotation.OptIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.state.rememberRepeatButtonState
import dev.anilbeesetti.nextplayer.core.ui.R as coreUiR
import dev.anilbeesetti.nextplayer.feature.player.LocalControlsVisibilityState
import dev.anilbeesetti.nextplayer.feature.player.state.MediaPresentationState

@OptIn(UnstableApi::class)
@Composable
fun LoopButton(player: Player, modifier: Modifier = Modifier) {
    val state = rememberRepeatButtonState(player)
    val controlsVisibilityState = LocalControlsVisibilityState.current

    PlayerButton(
        modifier = modifier,
        isEnabled = state.isEnabled,
        onClick = {
            state.onClick()
            controlsVisibilityState?.showControls()
        },
    ) {
        Icon(
            painter = repeatModeIconPainter(state.repeatModeState),
            contentDescription = repeatModeContentDescription(state.repeatModeState),
        )
    }
}

@Composable
private fun repeatModeIconPainter(repeatMode: @Player.RepeatMode Int): Painter {
    return when (repeatMode) {
        Player.REPEAT_MODE_OFF -> painterResource(coreUiR.drawable.ic_loop_off)
        Player.REPEAT_MODE_ONE -> painterResource(coreUiR.drawable.ic_loop_one)
        else -> painterResource(coreUiR.drawable.ic_loop_all)
    }
}

@Composable
private fun repeatModeContentDescription(repeatMode: @Player.RepeatMode Int): String {
    return when (repeatMode) {
        Player.REPEAT_MODE_OFF -> stringResource(coreUiR.string.loop_mode_off)
        Player.REPEAT_MODE_ONE -> stringResource(coreUiR.string.loop_mode_one)
        else -> stringResource(coreUiR.string.loop_mode_all)
    }
}

@Composable
fun AbRepeatButton(
    state: MediaPresentationState,
    modifier: Modifier = Modifier,
) {
    val controlsVisibilityState = LocalControlsVisibilityState.current
    val pointA = state.abRepeatPointA
    val pointB = state.abRepeatPointB

    val (text, tint) = when {
        pointA != null && pointB != null -> "A-B" to MaterialTheme.colorScheme.primary
        pointA != null -> "A-" to MaterialTheme.colorScheme.secondary
        else -> "A-B" to MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    }

    PlayerButton(
        modifier = modifier,
        onClick = {
            state.toggleAbRepeat()
            controlsVisibilityState?.showControls()
        },
    ) {
        Text(
            text = text,
            color = tint,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
        )
    }
}
