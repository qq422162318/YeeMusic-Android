package com.yeemusic.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.yeemusic.android.api.Song
import com.yeemusic.android.ui.AppActions
import com.yeemusic.android.ui.components.CoverImage
import com.yeemusic.android.ui.components.EmptyView
import com.yeemusic.android.ui.components.ErrorView
import com.yeemusic.android.ui.components.SongRow
import com.yeemusic.android.ui.components.currentSongId
import com.yeemusic.android.ui.theme.YeeMusicTheme
import java.util.Calendar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

private sealed interface HomeData {
    data object Loading : HomeData
    data class Ok(val daily: List<Song>, val fm: List<Song>, val recent: List<Song>) : HomeData
    data class Error(val message: String) : HomeData
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(actions: AppActions, onSearch: () -> Unit) {
    var data by remember { mutableStateOf<HomeData>(HomeData.Loading) }
    val scope = rememberCoroutineScope()
    val currentId = actions.currentSongId()

    suspend fun fetch() {
        try {
            data = coroutineScope {
                val daily = async { homeSongs { actions.musicApi.dailyRecommend() } }
                val fm = async { homeSongs { actions.musicApi.personalFm() } }
                val recent = async { homeSongs { actions.musicApi.recentSongs() } }
                HomeData.Ok(daily.await(), fm.await(), recent.await())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            data = HomeData.Error(e.message ?: "加载失败")
        }
    }

    LaunchedEffect(Unit) { fetch() }

    Scaffold(topBar = { HomeTopBar(onSearch) }) { padding ->
        when (val d = data) {
            HomeData.Loading -> Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator(strokeWidth = 3.dp) }

            is HomeData.Error -> ErrorView(
                d.message,
                onRetry = { data = HomeData.Loading; scope.launch { fetch() } },
                modifier = Modifier.padding(padding),
            )

            is HomeData.Ok -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(top = 8.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "greeting", contentType = "hero") {
                    GreetingHeader(
                        onPlay = { actions.playFirst(d.daily.ifEmpty { d.fm }.ifEmpty { d.recent }) },
                        canPlay = d.daily.isNotEmpty() || d.fm.isNotEmpty() || d.recent.isNotEmpty(),
                    )
                }
                item(key = "search", contentType = "search") { HomeSearchShortcut(onSearch) }
                item(key = "daily-header", contentType = "section") {
                    HomeSectionHeader("每日推荐", "为今天的你，挑选好声音", d.daily.takeIf { it.isNotEmpty() }?.let {
                        { actions.playFirst(it) }
                    })
                }
                item(key = "daily", contentType = "tiles") {
                    SongTileRow(d.daily) { actions.playSong(it, d.daily) }
                }
                item(key = "fm-header", contentType = "section") {
                    HomeSectionHeader("私人电台", "下一首，也许就是心动", d.fm.takeIf { it.isNotEmpty() }?.let {
                        { actions.playFirst(it) }
                    })
                }
                item(key = "fm", contentType = "tiles") {
                    SongTileRow(d.fm) { actions.playSong(it, d.fm) }
                }
                if (d.recent.isNotEmpty()) {
                    item(key = "recent-header", contentType = "section") {
                        HomeSectionHeader("最近播放", "熟悉的旋律，值得再听一次") { actions.playFirst(d.recent) }
                    }
                    itemsIndexed(d.recent, key = { i, song -> "recent-${song.id}-$i" }, contentType = { _, _ -> "song" }) { _, song ->
                        SongRow(
                            song = song,
                            isCurrent = currentId == song.id,
                            onClick = { actions.playSong(song, d.recent) },
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                }
                if (d.daily.isEmpty() && d.fm.isEmpty() && d.recent.isEmpty()) {
                    item(key = "empty") { EmptyView("暂时没有内容，登录账号或检查 API 地址后再试") }
                }
            }
        }
    }
}

private suspend fun homeSongs(fetch: suspend () -> List<Song>): List<Song> = try {
    fetch()
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    emptyList()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeTopBar(onSearch: () -> Unit) {
    TopAppBar(
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Headphones, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(21.dp))
                    }
                }
                Spacer(Modifier.width(10.dp))
                Text("Yee Music", style = MaterialTheme.typography.titleLarge)
            }
        },
        actions = {
            IconButton(onClick = onSearch) { Icon(Icons.Filled.Search, contentDescription = "搜索") }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
    )
}

@Composable
private fun GreetingHeader(onPlay: () -> Unit, canPlay: Boolean) {
    val hour = remember { Calendar.getInstance().get(Calendar.HOUR_OF_DAY) }
    val greeting = when (hour) {
        in 5..10 -> "早上好"
        in 11..13 -> "中午好"
        in 14..17 -> "下午好"
        else -> "晚上好"
    }
    val day = remember { Calendar.getInstance().get(Calendar.DAY_OF_MONTH).toString().padStart(2, '0') }
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        shape = RoundedCornerShape(28.dp),
        color = Color(0xFF2853B9),
    ) {
        Box(Modifier.background(Brush.linearGradient(listOf(Color(0xFF3776EA), Color(0xFF344B9B))))) {
            Box(
                Modifier.size(170.dp).align(Alignment.TopEnd).background(Color.White.copy(alpha = 0.05f), CircleShape),
            )
            Column(Modifier.padding(24.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.CalendarToday, null, tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("$day / 今日的音乐时光", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.8f))
                }
                Spacer(Modifier.height(18.dp))
                Text("$greeting，\n让音乐陪着你。", style = MaterialTheme.typography.headlineMedium, color = Color.White)
                Spacer(Modifier.height(8.dp))
                Text("从一首喜欢的歌开始", style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.8f))
                Spacer(Modifier.height(20.dp))
                Surface(onClick = onPlay, enabled = canPlay, shape = CircleShape, color = Color.White, contentColor = Color(0xFF2853B9)) {
                    Row(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.PlayArrow, null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("开始聆听", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeSearchShortcut(onSearch: () -> Unit) {
    Surface(
        onClick = onSearch,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Search, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Text("寻找你的下一首心动", modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun HomeSectionHeader(title: String, subtitle: String, onPlayAll: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (onPlayAll != null) {
            TextButton(onClick = onPlayAll, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Icon(Icons.Filled.PlayArrow, null, modifier = Modifier.size(18.dp))
                Text("播放", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun SongTileRow(songs: List<Song>, onPick: (Song) -> Unit) {
    if (songs.isEmpty()) {
        Text("暂无推荐，稍后再来听听", modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val visibleSongs = remember(songs) { songs.take(12) }
    LazyRow(
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        itemsIndexed(visibleSongs, key = { i, song -> "tile-${song.id}-$i" }, contentType = { _, _ -> "tile" }) { _, song ->
            Surface(
                onClick = { onPick(song) },
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.width(148.dp),
            ) {
                Column(Modifier.padding(8.dp)) {
                    Box {
                        CoverImage(song.coverUrl, size = 132, shape = RoundedCornerShape(14.dp), label = song.name)
                        Box(
                            Modifier.align(Alignment.BottomEnd).padding(8.dp).size(30.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.35f)),
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(20.dp)) }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(song.name.ifBlank { "未知歌曲" }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                    Text(song.artistsText.ifBlank { "未知歌手" }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}

@Preview(name = "首页 · 浅色", widthDp = 360, heightDp = 800, showBackground = true)
@Composable
private fun HomeLightPreview() {
    YeeMusicTheme("light") { HomeDesignPreview() }
}

@Preview(name = "首页 · 深色", widthDp = 360, heightDp = 800, showBackground = true)
@Composable
private fun HomeDarkPreview() {
    YeeMusicTheme("dark") { HomeDesignPreview() }
}

@Composable
private fun HomeDesignPreview() {
    val songs = remember { listOf(Song(id = 1, name = "慢慢喜欢你"), Song(id = 2, name = "在音乐里相遇")) }
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            HomeTopBar {}
            GreetingHeader({}, true)
            HomeSearchShortcut {}
            HomeSectionHeader("每日推荐", "为今天的你，挑选好声音") {}
            SongTileRow(songs) {}
        }
    }
}
