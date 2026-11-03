package com.yeemusic.android.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.yeemusic.android.api.Song
import com.yeemusic.android.player.LyricParser

/** Album art with a generated placeholder when the url is missing or broken. */
@Composable
fun CoverImage(
    url: String?,
    modifier: Modifier = Modifier,
    size: Int = 48,
    shape: Shape = RoundedCornerShape(12.dp),
    label: String = "",
) {
    val context = LocalContext.current
    val request = remember(context, url, size) {
        ImageRequest.Builder(context)
            .data(url)
            .size((size * context.resources.displayMetrics.density).toInt().coerceAtLeast(1))
            .crossfade(180)
            .build()
    }
    val target = modifier.size(size.dp).clip(shape)
    Box(target) {
        PlaceholderCover(Modifier.fillMaxSize(), label)
        if (!url.isNullOrBlank()) {
            AsyncImage(
                model = request,
                contentDescription = label.ifBlank { "封面" },
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

@Composable
private fun PlaceholderCover(modifier: Modifier, label: String) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = modifier.background(
            Brush.linearGradient(listOf(colors.surfaceContainerHigh, colors.surfaceContainer)),
        ),
        contentAlignment = Alignment.Center,
    ) {
        if (label.isBlank()) {
            Icon(
                Icons.Filled.Headphones,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(0.45f),
                tint = colors.outline,
            )
        } else {
            Text(text = label.take(1), style = MaterialTheme.typography.titleLarge, color = colors.outline)
        }
    }
}

/** One line of a song list. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongRow(
    song: Song,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isCurrent: Boolean = false,
    showCover: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val durationText = remember(song.durationMs) { LyricParser.formatDuration(song.durationMs) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (isCurrent) colors.primaryContainer.copy(alpha = 0.55f) else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showCover) {
            CoverImage(song.coverUrl, size = 48, label = song.name)
            Spacer(Modifier.width(12.dp))
        }

        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = song.name.ifBlank { "未知歌曲" },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Medium,
                color = if (isCurrent) colors.primary else colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = song.artistsText.ifBlank { "未知歌手" },
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (song.durationMs > 0 && actions == null) {
            Spacer(Modifier.width(8.dp))
            Text(durationText, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
        }
        if (actions != null) {
            actions()
        } else if (isCurrent) {
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Filled.Headphones, contentDescription = "当前歌曲", modifier = Modifier.size(18.dp), tint = colors.primary)
        }
    }
}

@Composable
fun ErrorView(
    message: String,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(message, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (onRetry != null) {
            Spacer(Modifier.height(12.dp))
            Surface(onClick = onRetry, shape = RoundedCornerShape(16.dp)) {
                Text("重试", modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            }
        }
    }
}

@Composable
fun EmptyView(message: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Filled.Headphones,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.height(12.dp))
        Text(message, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun RowAction(
    onClick: () -> Unit,
    icon: ImageVector,
    label: String,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    IconButton(onClick = onClick) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(20.dp))
    }
}

/** A horizontal row of song tiles for the home page. */
/** "X 万" style compact count. */
fun compactCount(value: Long): String = when {
    value >= 1_0000_0000L -> String.format("%.1f亿", value / 1_0000_0000.0)
    value >= 10000L -> String.format("%.1f万", value / 10000.0)
    else -> value.toString()
}

/**
 * Joins only the bits that are present, so a missing author (nodes omit the
 * playlist `creator` for some users) doesn't leave a dangling " · 5首".
 */
fun dotted(vararg parts: String?): String =
    parts.mapNotNull { it?.takeIf { s -> s.isNotBlank() } }.joinToString(" · ")

/** Binary units, so it agrees with what the filesystem reports. */
fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return if (unit == 0) "${value.toLong()} B" else String.format("%.1f %s", value, units[unit])
}
