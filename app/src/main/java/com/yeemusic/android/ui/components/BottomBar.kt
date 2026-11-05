package com.yeemusic.android.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yeemusic.android.player.PlayerSnapshot

/** A compact floating player; the full player owns seeking and previous-track on narrow screens. */
@Composable
fun MiniPlayerBar(
    snapshot: PlayerSnapshot,
    onPlay: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onOpen: () -> Unit,
    onQueue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!snapshot.hasMedia) return

    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 3.dp,
    ) {
        BoxWithConstraints {
            val showPrevious = maxWidth >= 420.dp
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            ) {
                Row(
                    modifier = Modifier.weight(1f).clickable(onClick = onOpen),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CoverImage(snapshot.coverUrl, size = 46, label = snapshot.title)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(snapshot.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                        Text(snapshot.artistText, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (showPrevious) {
                    IconButton(onClick = onPrev) { Icon(Icons.Filled.SkipPrevious, contentDescription = "上一首") }
                }
                FilledIconButton(onClick = onPlay, shape = CircleShape, modifier = Modifier.size(48.dp)) {
                    Icon(
                        if (snapshot.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (snapshot.isPlaying) "暂停" else "播放",
                        modifier = Modifier.size(26.dp),
                    )
                }
                IconButton(onClick = onNext) { Icon(Icons.Filled.SkipNext, contentDescription = "下一首") }
                IconButton(onClick = onQueue) { Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = "播放列表", modifier = Modifier.size(22.dp)) }
            }
        }
    }
}
