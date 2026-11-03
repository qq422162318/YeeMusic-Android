package com.yeemusic.android.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yeemusic.android.ui.AppActions
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Song lists only need the selected id, not the half-second playback ticker. */
@Composable
fun AppActions.currentSongId(): Long? {
    val ids = remember(player) {
        player.state.map { it.song?.id }.distinctUntilChanged()
    }
    val initialId = remember(player) { player.state.value.song?.id }
    return ids.collectAsStateWithLifecycle(initialValue = initialId).value
}
