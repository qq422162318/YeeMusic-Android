package com.yeemusic.android.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yeemusic.android.api.Song
import com.yeemusic.android.player.PlayerSnapshot
import com.yeemusic.android.player.RepeatMode

/** Playback queue with remove / move-up controls and the repeat toggle. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueSheet(
    snapshot: PlayerSnapshot,
    onDismiss: () -> Unit,
    onPick: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onMoveUp: (Int) -> Unit,
    onRepeat: () -> Unit,
    onClear: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val colors = MaterialTheme.colorScheme

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        ) {
            Text(
                "播放列表 · ${snapshot.queue.size}",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onRepeat) {
                Icon(
                    when (snapshot.repeat) {
                        RepeatMode.SINGLE_LOOP -> Icons.Filled.RepeatOne
                        RepeatMode.SHUFFLE -> Icons.Filled.Shuffle
                        else -> Icons.Filled.Repeat
                    },
                    contentDescription = "播放模式",
                    tint = if (snapshot.repeat != RepeatMode.SEQUENCE) colors.primary else colors.onSurfaceVariant,
                )
            }
            TextButton(onClick = onClear) { Text("清空") }
        }

        if (snapshot.queue.isEmpty()) {
            Text(
                "播放列表为空",
                modifier = Modifier.padding(16.dp),
                color = colors.onSurfaceVariant,
            )
        } else {
            LazyColumn(modifier = Modifier.height(480.dp)) {
                itemsIndexed(snapshot.queue, key = { i, s -> "${i}-${s.id}" }) { index, song ->
                    QueueRow(
                        index = index + 1,
                        song = song,
                        isCurrent = index == snapshot.current,
                        onClick = { onPick(index) },
                        onMoveUp = { onMoveUp(index) },
                        onRemove = { onRemove(index) },
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun QueueRow(
    index: Int,
    song: Song,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onMoveUp: () -> Unit,
    onRemove: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            "$index",
            style = MaterialTheme.typography.labelMedium,
            color = if (isCurrent) colors.primary else colors.outline,
            modifier = Modifier.width(28.dp),
        )
        CoverImage(song.coverUrl, size = 36, label = song.name)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                song.name.ifBlank { "未知歌曲" },
                maxLines = 1,
                color = if (isCurrent) colors.primary else colors.onSurface,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                song.artistsText,
                maxLines = 1,
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
            )
        }
        RowAction(onMoveUp, Icons.Filled.KeyboardArrowUp, "上移")
        RowAction(onRemove, Icons.Filled.Close, "移除")
        Spacer(Modifier.width(4.dp))
    }
}
