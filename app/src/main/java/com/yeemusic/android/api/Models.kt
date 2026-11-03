package com.yeemusic.android.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/**
 * Wire models for the NeteaseCloudMusicAPI Enhanced backend.
 *
 * Everything is nullable / defaulted on purpose: this third-party API is loose
 * and evolves without notice, so a missing field must never crash the app.
 */

@Serializable
data class Artist(
    val id: Long = 0,
    val name: String = "",
    val picUrl: String? = null,
    val coverImgUrl: String? = null,
    /** `/artist/detail` puts the picture in `cover` instead. */
    @SerialName("cover")
    val coverPic: String? = null,
    val trans: String? = null,
    val tag: String? = null,
    val brief: String? = null,
    val alias: List<String> = emptyList(),
    val albumSize: Int = 0,
    val songSize: Int = 0,
    /** `/artist/detail` reports the song count in `musicSize`. */
    val musicSize: Int = 0,
    val videoCount: Int = 0,
) {
    val cover: String? get() = picUrl ?: coverImgUrl ?: coverPic
    val songCount: Int get() = if (songSize > 0) songSize else musicSize
    val aliasText: String? get() =
        if (alias.isEmpty()) null else alias.joinToString(" / ")
}

@Serializable
data class BrQuality(val br: Int = 0, val vbr: Int = 0)

/** Album metadata nested inside a Song. */
@Serializable
data class SongAlbum(
    val id: Long = 0,
    val name: String = "",
    val picUrl: String? = null,
    val picId: Long = 0,
    val publishTime: Long = 0,
    val company: String? = null,
)

/** Album as a top-level resource (artist/album, search). */
@Serializable
data class Album(
    val id: Long = 0,
    val name: String = "",
    val picUrl: String? = null,
    val picId: Long = 0,
    val artists: List<Artist> = emptyList(),
    val artistText: String? = null,
    val publishTime: Long = 0,
    val totalNum: Int = 0,
    val status: Int = 0,
    val copyrightId: Int = 0,
    val description: String? = null,
    val company: String? = null,
) {
    val cover: String? get() = picUrl
    val artistTextJoined: String
        get() = artistText ?: artists.joinToString(" / ") { it.name }
}

@Serializable
data class Song(
    val id: Long = 0,
    val name: String = "",
    val ar: List<Artist> = emptyList(),
    val al: SongAlbum? = null,
    val ht: Artist? = null,
    val duration: Long = 0,
    val dt: Long = 0,
    val pop: Double = 0.0,
    val score: Int = 0,
    val fee: Int = 0,
    val status: Int = 0,
    val copyrightId: Int = 0,
    val type: String? = null,
    val encodeType: String? = null,
    val sr: Int = 0,
    val no: Int = 0,
    val hash: String? = null,
    val rtUrl: String? = null,
    /** Local-file sources. Blank for songs that come from the API. */
    val uri: String = "",
    val path: String = "",
    val h: BrQuality? = null,
    val l: BrQuality? = null,
    val m: BrQuality? = null,
    val sq: BrQuality? = null,
    val hr: BrQuality? = null,
) {
    /** ms, preferring the explicit `dt` field when present. */
    val durationMs: Long get() = if (dt > 0) dt else duration

    val artistsText: String get() = ar.joinToString(" / ") { it.name }

    val coverUrl: String? get() = al?.picUrl

    val idStr: String get() = id.toString()

    /** Fee: 0 = free, 1 = VIP, 8 = purchased album. */
    val isVipLocked: Boolean get() = fee == 1 || fee == 8

    /** Best non-lost quality object, high to low. */
    val bestBr: Int
        get() = sq?.br?.takeIf { it > 0 }
            ?: hr?.br?.takeIf { it > 0 }
            ?: h?.br?.takeIf { it > 0 }
            ?: m?.br?.takeIf { it > 0 }
            ?: l?.br?.takeIf { it > 0 }
            ?: 0
}

/** `song/url/v1` and `song/download/url/v1` payload. */
@Serializable
data class SongUrlData(
    val id: Long = 0,
    val url: String? = null,
    val br: Int = 0,
    val size: Long = 0,
    val md5: String? = null,
    val code: Int = 200,
    val type: String? = null,
    val encondeType: String? = null,
    val expi: Int = 0,
    val level: String = "",
    val fee: Int = 0,
    val sr: Int = 0,
    val gain: Double = 0.0,
    val peak: Double = 0.0,
    val srText: String? = null,
    /** `{start, end}` for a VIP 试听 fragment; the URL is real but 30 s long. */
    val freeTrialInfo: JsonElement? = null,
) {
    val playable: Boolean get() = !url.isNullOrBlank() && code == 200

    /**
     * True when the node handed back a 试听 clip instead of the whole track.
     *
     * Only a real `{start,end,...}` object counts as a 试听. The unblocked
     * response carries the literal string `"null"` — that is a full track, not
     * a trial, and must NOT be read as one.
     */
    val freeTrial: Boolean
        get() = (freeTrialInfo as? JsonObject)?.isEmpty() == false

    /** `flac` for lossless levels, taken from the URL so a downgrade is visible. */
    val isFlac: Boolean get() = type?.lowercase() == "flac" || url.orEmpty().contains(".flac")
    val bitrateLabel: String get() = when (level.lowercase()) {
        "standard" -> "128kbps"
        "higher" -> "192kbps"
        "exhigh" -> "320kbps"
        "lossless" -> "FLAC"
        "hires" -> "Hi-Res"
        "jq" -> "JOS 320kbps"
        "jye" -> "JOS FLAC"
        "sk" -> "SK FLAC"
        else -> if (br > 0) "${br / 1000}kbps" else type ?: "-"
    }
}

@Serializable
data class Playlist(
    val id: Long = 0,
    val name: String = "",
    val coverImgUrl: String? = null,
    val coverImgId: Long = 0,
    val creator: PlaylistCreator? = null,
    val playCount: Long = 0,
    val trackCount: Int = 0,
    val description: String? = null,
    val tags: List<String> = emptyList(),
    val tracks: List<Song> = emptyList(),
    val subscribedCount: Int = 0,
    val tagsObj: List<PlaylistTag> = emptyList(),
) {
    val cover: String? get() = coverImgUrl ?: creator?.cover
    val creatorName: String get() = creator?.displayName ?: ""
    val nameDisplay: String get() = name.ifBlank { "未命名歌单" }
}

/**
 * A playlist's `creator` is a *user profile*, not an artist: it carries
 * `nickname`/`avatarUrl` rather than `name`/`picUrl`. It was modelled as
 * [Artist] before, which made `creatorName` come back empty for every
 * playlist — the reason rows and detail pages showed " · 158首" with no author.
 * Both spellings are read so nodes that still send the artist shape work.
 */
@Serializable
data class PlaylistCreator(
    val id: Long = 0,
    val userId: Long = 0,
    val name: String = "",
    val nickname: String = "",
    val picUrl: String? = null,
    val coverImgUrl: String? = null,
    val avatarUrl: String? = null,
) {
    val displayName: String get() = name.ifBlank { nickname }
    val cover: String? get() = picUrl ?: coverImgUrl ?: avatarUrl
}

@Serializable
data class PlaylistTag(val name: String = "")

@Serializable
data class TrackId(val id: Long = 0)

@Serializable
data class LyricBlock(
    val lyric: String = "",
    val version: Int = 0,
    val sc: String? = null,
)

@Serializable
data class LyricData(
    val code: Int = 0,
    val lrc: LyricBlock = LyricBlock(),
    val tlyric: LyricBlock = LyricBlock(),
    val klyric: LyricBlock = LyricBlock(),
    val rl: LyricBlock = LyricBlock(),
    val rlyric: LyricBlock = LyricBlock(),
    val lyricInfo: LyricBlock = LyricBlock(),
) {
    val main: String get() = lrc.lyric.ifBlank { lyricInfo.lyric }
    val translation: String get() = tlyric.lyric.ifBlank { rlyric.lyric }
    val romaji: String get() = rl.lyric.ifBlank { klyric.lyric }
    val hasAny: Boolean get() = main.isNotBlank() || translation.isNotBlank() || romaji.isNotBlank()
}

@Serializable
data class UserAccount(val id: Long = 0, val userName: String = "")

@Serializable
data class UserProfile(
    val nickname: String = "",
    val avatarUrl: String? = null,
    val signature: String? = null,
    val province: Int = 0,
    val city: Int = 0,
    val gender: Int = 0,
)

@Serializable
data class User(
    val account: UserAccount = UserAccount(),
    val profile: UserProfile = UserProfile(),
    val detail: UserDetail = UserDetail(),
    val playlistCount: Int = 0,
    val createPlaylistCount: Int = 0,
    val djCount: Int = 0,
    val followCount: Int = 0,
    val followeds: Int = 0,
    val mutualCount: Int = 0,
    val setting: UserSetting = UserSetting(),
) {
    val nickname: String get() = profile.nickname.ifBlank { "已登录用户" }
    val avatarUrl: String? get() = profile.avatarUrl
    val signature: String get() = profile.signature ?: ""
    val level: Int get() = detail.level
    val isVip: Boolean get() = detail.level >= 4
}

@Serializable
data class UserDetail(
    val level: Int = 0,
    val exp: Int = 0,
    val points: Int = 0,
    val dailySignup: Boolean = false,
)

@Serializable
data class UserSetting(val likeOn: Boolean = true)

@Serializable
data class LikeStatus(val likeStatus: Boolean = false, val id: Long = 0)

/** `search/default` - the keyword chips shown before you type. */
@Serializable
data class SearchDefaultData(
    val showKeyword: String = "",
    val realkeyword: String = "",
)

@Serializable
data class HomepageBlock(
    val blockCode: String = "",
    val title: String? = null,
    val subBlockList: List<SubBlock> = emptyList(),
)

@Serializable
data class SubBlock(
    val subBlockCode: String = "",
    val dataList: List<HomeItem> = emptyList(),
)

@Serializable
data class HomeItem(
    val resourceType: String = "",
    val id: Long = 0,
    val name: String = "",
    val picUrl: String? = null,
    val song: Song? = null,
    val songData: SongData? = null,
    val resource: HomeResource? = null,
)

@Serializable
data class SongData(
    val id: Long = 0,
    val name: String = "",
    val artists: List<Artist> = emptyList(),
    val albumName: String? = null,
    val duration: Int = 0,
)

@Serializable
data class HomeResource(
    val id: Long = 0,
    val name: String = "",
    val picUrl: String? = null,
)

/**
 * `/cloud/upload/token` answer.
 *
 * The file bytes go straight to [uploadUrl] — NetEase's NOS object storage,
 * over plain HTTP — and never pass through the API node. That is why the node's
 * request-body limit is not a factor for large FLACs.
 */
@Serializable
data class CloudUploadToken(
    val needUpload: Boolean = true,
    val songId: String = "",
    val uploadToken: String = "",
    val objectKey: String = "",
    val resourceId: String = "",
    val uploadUrl: String = "",
    val bucket: String = "",
    val md5: String = "",
    val fileSize: Long = 0,
    val filename: String = "",
)

/** Everything [MusicApi.cloudAll] knows about the drive. */
data class UserCloudData(
    val items: List<CloudSongItem> = emptyList(),
    val count: Int = 0,
    val usedBytes: Long = 0,
    val totalBytes: Long = 0,
) {
    /** Rows the player can actually queue. */
    val songs: List<Song> get() = items.map { it.asSong }
}

/**
 * One entry of `/user/cloud`.
 *
 * `simpleSong` is the matched public track. For uploads that matched nothing
 * (`t=1`) it comes back almost empty, so the fields the node parsed out of the
 * file — [songName], [artist], [album] — are what actually identify the row.
 */
@Serializable
data class CloudSongItem(
    val simpleSong: Song = Song(),
    val songId: Long = 0,
    val songName: String = "",
    val artist: String = "",
    val album: String = "",
    val fileName: String = "",
    val fileSize: Long = 0,
    val bitrate: Int = 0,
    val addTime: Long = 0,
    val cover: Int = 0,
    val coverId: String = "",
    val lyricId: String = "",
    val version: Int = 0,
) {
    /**
     * Folds the cloud entry into something the player and [com.yeemusic.android.ui.components.SongRow]
     * can render directly, falling back to the file metadata when there is no
     * public track behind it.
     */
    val asSong: Song
        get() {
            val s = simpleSong
            val hasArtist = s.ar.any { it.name.isNotBlank() }
            return s.copy(
                id = if (s.id != 0L) s.id else songId,
                name = s.name.ifBlank { songName.ifBlank { fileName } },
                ar = if (hasArtist) s.ar else listOf(Artist(id = 0, name = artist.ifBlank { "未知歌手" })),
                al = if (s.al?.name.isNullOrBlank()) {
                    SongAlbum(id = s.al?.id ?: 0L, name = album.ifBlank { "未知专辑" }, picUrl = s.al?.picUrl)
                } else {
                    s.al
                },
            )
        }
}

@Serializable
data class LocalMusic(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val albumId: Long = 0,
    val duration: Long = 0,
    val path: String = "",
    val uri: String = "",
    val dateModified: Long = 0,
    val _id: Long = 0,
)

@Serializable
data class SearchSuggestResult(
    val keyword: String = "",
)

// ---------------------------------------------------------------------------
// Per-endpoint response envelopes. Field names differ across the API:
// `data`, `result`, `songs`, `resources` are all used as the payload carrier.
// ---------------------------------------------------------------------------

@Serializable
data class RespData<T>(
    val code: Int = 0,
    val message: String? = null,
    val msg: String? = null,
    val data: T? = null,
)

@Serializable
data class RespResult<T>(
    val code: Int = 0,
    val result: T? = null,
    val message: String? = null,
)

@Serializable
data class SongDetailResp(
    val code: Int = 0,
    val songs: List<Song> = emptyList(),
    val privileges: List<SongPrivilege> = emptyList(),
    val message: String? = null,
)

@Serializable
data class SongPrivilege(
    val id: Long = 0,
    val name: String = "",
    val status: Int = 0,
    val fee: Int = 0,
    val pl: Int = 0,
    val dl: Int = 0,
    val cp: Int = 0,
    val fl: Int = 0,
    val cl: Int = 0,
)

@Serializable
data class SongUrlResp(
    val code: Int = 0,
    val data: List<SongUrlData> = emptyList(),
    val message: String? = null,
)

@Serializable
data class SongQualityResp(
    val code: Int = 0,
    val success: Boolean = false,
    val data: SongQualityMap = SongQualityMap(),
    val message: String? = null,
)

@Serializable
data class SongQualityMap(
    val l: BrQuality = BrQuality(),
    val h: BrQuality = BrQuality(),
    val m: BrQuality = BrQuality(),
    val sq: BrQuality = BrQuality(),
    val hr: BrQuality = BrQuality(),
    val jm: BrQuality = BrQuality(),
    val je: BrQuality = BrQuality(),
)

@Serializable
data class DailyRecommendResp(
    val code: Int = 0,
    val data: DailyRecommendData = DailyRecommendData(),
    val message: String? = null,
)

@Serializable
data class DailyRecommendData(
    val dailySongs: List<Song> = emptyList(),
    val fromCache: Boolean = false,
    val list: List<Song> = emptyList(),
    val createTime: Long = 0,
) {
    val songs: List<Song> get() = if (dailySongs.isNotEmpty()) dailySongs else list
}

@Serializable
data class PlaylistDetailResp(
    val code: Int = 0,
    val playlist: Playlist = Playlist(),
    val privileges: List<SongPrivilege> = emptyList(),
    val tracks: List<Song> = emptyList(),
    val message: String? = null,
)

/**
 * `/playlist/track/all` answers `{"songs": [...], "privileges": [...], "code": 200}`.
 * Older nodes put the tracks in `tracks` or inside `playlist.tracks` instead —
 * that is where this response used to look, so every playlist opened with no
 * songs at all (the "我的歌单" page came up blank). All three are read.
 */
@Serializable
data class PlaylistTrackAllResp(
    val code: Int = 0,
    val songs: List<Song> = emptyList(),
    val playlist: Playlist = Playlist(),
    val tracks: List<Song> = emptyList(),
    val message: String? = null,
)

@Serializable
data class UserPlaylistResp(
    val code: Int = 0,
    val playlist: List<Playlist> = emptyList(),
    val message: String? = null,
)

@Serializable
data class SearchDefaultResp(
    val code: Int = 0,
    val data: SearchDefaultData = SearchDefaultData(),
)

@Serializable
data class SearchSuggestResp(
    val code: Int = 0,
    val result: SearchSuggestAll = SearchSuggestAll(),
)

@Serializable
data class SearchSuggestAll(val allMatch: List<SearchSuggestResult> = emptyList())

@Serializable
data class HomepageResp(
    val code: Int = 0,
    val data: HomepageData? = null,
    val message: String? = null,
)

@Serializable
data class HomepageData(
    val cursor: String? = null,
    val blocks: List<HomepageBlock> = emptyList(),
)

@Serializable
data class RecentListenResp(
    val code: Int = 0,
    val data: RecentListenData? = null,
    val message: String? = null,
)

@Serializable
data class RecentListenData(val resources: List<HomeItem> = emptyList())

@Serializable
data class QrCreateResp(val qr: String = "")

@Serializable
data class CaptchaResp(val img: String = "", val captchaId: String = "")

/** Raw pass-through for endpoints we don't model in full detail. */
@Serializable
data class RawResp(val raw: JsonElement = kotlinx.serialization.json.JsonNull)
