package com.yeemusic.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yeemusic.android.player.LyricParser
import com.yeemusic.android.player.ParsedLyric
import com.yeemusic.android.player.PlayerSnapshot
import com.yeemusic.android.player.RepeatMode
import com.yeemusic.android.ui.AppActions
import com.yeemusic.android.ui.components.CoverImage
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Keep the playback clock in small children instead of recomposing the whole page. */
@Composable
fun FullPlayerScreen(actions: AppActions, onDismiss: () -> Unit, onQueue: () -> Unit) {
    val metadata = remember(actions.player) {
        actions.player.state.map { it.copy(positionMs = 0, volume = 0) }.distinctUntilChanged()
    }
    val initialMetadata = remember(actions.player) { actions.player.state.value.copy(positionMs = 0, volume = 0) }
    val snapshot by metadata.collectAsStateWithLifecycle(initialValue = initialMetadata)
    val likedIds by actions.likedIds.collectAsStateWithLifecycle()
    var showLyrics by rememberSaveable { mutableStateOf(false) }
    var dismissY by remember { mutableFloatStateOf(0f) }
    val dismiss by rememberUpdatedState(onDismiss)
    val threshold = with(LocalDensity.current) { 96.dp.toPx() }
    val colors = MaterialTheme.colorScheme

    if (!snapshot.hasMedia) {
        EmptyPlayer(onDismiss)
        return
    }

    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(colors.primaryContainer.copy(alpha = 0.65f), colors.background, colors.background)),
        ),
    ) {
        // Only the header dismisses the page; sliders and lyric scrolling own their gestures.
        LazyColumn(
            modifier = Modifier.fillMaxSize().graphicsLayer { translationY = dismissY },
            contentPadding = PaddingValues(bottom = 20.dp),
        ) {
            item(key = "header") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
                        .pointerInput(threshold) {
                            detectVerticalDragGestures(
                                onVerticalDrag = { _, amount -> dismissY = (dismissY + amount).coerceIn(0f, threshold * 2f) },
                                onDragEnd = { if (dismissY > threshold) dismiss() else dismissY = 0f },
                                onDragCancel = { dismissY = 0f },
                            )
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "收起播放器") }
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("正在播放", style = MaterialTheme.typography.titleSmall)
                        Text(snapshot.repeat.label, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                    }
                    IconButton(onClick = onQueue) { Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = "播放列表") }
                }
            }
            item(key = "visual") {
                PlayerVisual(actions, snapshot, showLyrics, onToggleLyrics = { showLyrics = !showLyrics })
            }
            item(key = "title") {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(snapshot.title, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(6.dp))
                        Text(snapshot.artistText, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    val id = snapshot.song?.id
                    IconButton(onClick = { if (id != null) actions.toggleLike(id) }) {
                        Icon(
                            if (id in likedIds) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                            contentDescription = if (id in likedIds) "取消喜欢" else "喜欢",
                            tint = if (id in likedIds) colors.primary else colors.onSurfaceVariant,
                        )
                    }
                }
            }
            item(key = "progress") { PlayerProgress(actions) }
            item(key = "transport") {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    IconButton(onClick = { actions.player.cycleRepeat() }) {
                        Icon(
                            when (snapshot.repeat) {
                                RepeatMode.SINGLE_LOOP -> Icons.Filled.RepeatOne
                                RepeatMode.SHUFFLE -> Icons.Filled.Shuffle
                                else -> Icons.Filled.Repeat
                            },
                            contentDescription = snapshot.repeat.label,
                            tint = if (snapshot.repeat == RepeatMode.SEQUENCE) colors.onSurfaceVariant else colors.primary,
                        )
                    }
                    IconButton(onClick = { actions.player.prev() }) { Icon(Icons.Filled.SkipPrevious, "上一首", Modifier.size(32.dp)) }
                    FilledIconButton(onClick = { actions.player.toggle() }, modifier = Modifier.size(72.dp), shape = CircleShape) {
                        if (snapshot.isLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(28.dp), color = colors.onPrimary, strokeWidth = 2.dp)
                        } else {
                            Icon(if (snapshot.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (snapshot.isPlaying) "暂停" else "播放", Modifier.size(36.dp))
                        }
                    }
                    IconButton(onClick = { actions.player.next() }) { Icon(Icons.Filled.SkipNext, "下一首", Modifier.size(32.dp)) }
                    IconButton(onClick = { showLyrics = !showLyrics }) {
                        Icon(Icons.Filled.Lyrics, if (showLyrics) "显示封面" else "显示歌词", tint = if (showLyrics) colors.primary else colors.onSurfaceVariant)
                    }
                }
            }
            item(key = "extras") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        IconButton(onClick = { actions.player.seekBy(-15_000L) }) { Icon(Icons.Filled.FastRewind, "后退 15 秒", tint = colors.onSurfaceVariant) }
                        Text("−15 秒", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                    }
                    Spacer(Modifier.width(24.dp))
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        IconButton(onClick = { actions.player.seekBy(15_000L) }) { Icon(Icons.Filled.FastForward, "前进 15 秒", tint = colors.onSurfaceVariant) }
                        Text("+15 秒", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                    }
                }
            }
            item(key = "volume") { PlayerVolume(actions) }
            snapshot.error?.let { message ->
                item(key = "error") {
                    Text(message, Modifier.fillMaxWidth().padding(24.dp), color = colors.error, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun PlayerVisual(actions: AppActions, snapshot: PlayerSnapshot, showLyrics: Boolean, onToggleLyrics: () -> Unit) {
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val visualHeight = (configuration.screenHeightDp * 0.39f).dp.coerceIn(180.dp, 380.dp)
    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth().height(visualHeight).padding(horizontal = 28.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (showLyrics) {
            PlayerLyrics(actions, Modifier.fillMaxSize(), onToggleLyrics)
        } else {
            val side = minOf(maxWidth, maxHeight - 12.dp).coerceAtLeast(1.dp)
            // The art is the affordance: tapping it opens the lyric sheet, and the
            // tap has to be consumed here rather than left for the dismiss handler
            // on the list above, which would otherwise treat it as a drag.
            Surface(
                onClick = onToggleLyrics,
                shape = RoundedCornerShape(28.dp),
                shadowElevation = 8.dp,
            ) {
                CoverImage(snapshot.coverUrl, size = side.value.toInt().coerceAtLeast(1), shape = RoundedCornerShape(28.dp), label = snapshot.title)
            }
        }
    }
}

private data class LyricFrame(val lyric: ParsedLyric, val index: Int)

@Composable
private fun PlayerLyrics(actions: AppActions, modifier: Modifier, onToggleLyrics: () -> Unit) {
    val frames = remember(actions.player) {
        actions.player.state.map {
            LyricFrame(it.lyric, if (it.lyric.unTimed) -1 else LyricParser.indexOfAt(it.lyric.lines, it.positionMs))
        }.distinctUntilChanged()
    }
    val initial = remember(actions.player) { actions.player.state.value }
    val frame by frames.collectAsStateWithLifecycle(
        initialValue = LyricFrame(initial.lyric, if (initial.lyric.unTimed) -1 else LyricParser.indexOfAt(initial.lyric.lines, initial.positionMs)),
    )
    val scale by actions.settings.lyricFontScale.collectAsStateWithLifecycle(initialValue = 100)
    val listState = rememberLazyListState()
    val colors = MaterialTheme.colorScheme
    val lineHeight = (42 * scale.coerceIn(60, 200) / 100f).dp.coerceAtLeast(36.dp)

    LaunchedEffect(frame.lyric, frame.index, scale) {
        if (frame.lyric.unTimed) {
            listState.scrollToItem(0)
        } else if (frame.index >= 0 && !listState.isScrollInProgress) {
            listState.animateScrollToItem(frame.index)
        }
    }
    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        if (frame.lyric.lines.isEmpty()) {
            // Still tappable: with no lyric to show there is nothing to read, so
            // the tap just takes the player back to the cover.
            Box(Modifier.fillMaxSize().clickable(onClick = onToggleLyrics), contentAlignment = Alignment.Center) {
                Text("暂无歌词，让旋律继续", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            val centering = ((maxHeight - lineHeight) / 2f).coerceAtLeast(0.dp)
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = centering),
            ) {
                itemsIndexed(frame.lyric.lines, key = { i, line -> "$i-${line.timeMs}" }, contentType = { _, _ -> "lyric" }) { index, line ->
                    val active = frame.lyric.unTimed || index == frame.index
                    Box(Modifier.fillMaxWidth().height(lineHeight), contentAlignment = Alignment.Center) {
                        Text(
                            line.text.ifBlank { "·" },
                            modifier = Modifier.padding(horizontal = 12.dp),
                            textAlign = TextAlign.Center,
                            fontSize = 17.sp * (scale.coerceIn(60, 200) / 100f),
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (active) colors.primary else colors.onSurfaceVariant.copy(alpha = 0.65f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

private data class PlaybackClock(val position: Long, val duration: Long)

@Composable
private fun PlayerProgress(actions: AppActions) {
    val clocks = remember(actions.player) {
        actions.player.state.map { PlaybackClock(it.positionMs, it.durationMs) }.distinctUntilChanged()
    }
    val initial = remember(actions.player) { actions.player.state.value }
    val clock by clocks.collectAsStateWithLifecycle(initialValue = PlaybackClock(initial.positionMs, initial.durationMs))
    var dragging by remember { mutableFloatStateOf(-1f) }
    val duration = clock.duration.coerceAtLeast(1L)
    val fraction = if (dragging >= 0f) dragging else (clock.position.toFloat() / duration).coerceIn(0f, 1f)
    Column(Modifier.padding(horizontal = 24.dp)) {
        Slider(
            value = fraction,
            enabled = clock.duration > 0,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                if (dragging >= 0f) actions.player.seekByFraction(dragging)
                dragging = -1f
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(LyricParser.formatDuration(if (dragging >= 0f) (dragging * duration).toLong() else clock.position), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(LyricParser.formatDuration(clock.duration), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PlayerVolume(actions: AppActions) {
    val volumes = remember(actions.player) { actions.player.state.map { it.volume }.distinctUntilChanged() }
    val initialVolume = remember(actions.player) { actions.player.state.value.volume }
    val volume by volumes.collectAsStateWithLifecycle(initialValue = initialVolume)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 28.dp).heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.AutoMirrored.Filled.VolumeDown, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(18.dp))
        Slider(value = volume / 100f, onValueChange = { actions.player.setVolume((it * 100f).toInt()) }, modifier = Modifier.weight(1f).padding(horizontal = 10.dp))
        Icon(Icons.AutoMirrored.Filled.VolumeUp, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun EmptyPlayer(onDismiss: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("未在播放", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))
            Surface(onClick = onDismiss, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Text("返回首页", modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
            }
        }
    }
}
