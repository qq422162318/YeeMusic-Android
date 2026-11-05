package com.yeemusic.android.api

import kotlinx.serialization.Serializable

/**
 * Per-endpoint envelopes, split out from [Models] because many endpoints reuse
 * the same payload type under a different top-level shape.
 */

@Serializable
data class CodeResp(val code: Int = 0, val message: String? = null, val msg: String? = null)

@Serializable
data class SearchResp(val code: Int = 0, val result: SearchResult = SearchResult())

@Serializable
data class SearchResult(
    val songs: List<Song> = emptyList(),
    val songCount: Int = 0,
    val playlists: List<Playlist> = emptyList(),
    val playlistCount: Int = 0,
    val artists: List<Artist> = emptyList(),
    val artistCount: Int = 0,
    val albums: List<Album> = emptyList(),
    val albumCount: Int = 0,
) {
    val hasAny: Boolean
        get() = songs.isNotEmpty() || playlists.isNotEmpty() ||
            artists.isNotEmpty() || albums.isNotEmpty()
}

/**
 * `/login/cellphone` answers `{code, cookie, profile, msg}` on success and
 * `{code, msg, message}` on failure, so the failure text lives in `msg`/`message`
 * rather than in an exception. `profile` is nullable because the failure body
 * omits it entirely and some nodes send an explicit `null`.
 */
@Serializable
data class LoginResp(
    val code: Int = 0,
    val cookie: String = "",
    val profile: UserProfile? = null,
    val message: String? = null,
    val msg: String? = null,
)

@Serializable
data class RefreshResp(val code: Int = 0, val cookie: String = "")

/** `account`/`profile` are `null` while unauthenticated — not omitted. */
@Serializable
data class LoginStatusData(
    val code: Int = 0,
    val account: UserAccount? = null,
    val profile: UserProfile? = null,
)

@Serializable
data class LoginStatusResp(val data: LoginStatusData = LoginStatusData())

@Serializable
data class QrKeyData(val code: Int = 0, val unikey: String = "")

@Serializable
data class QrKeyResp(val code: Int = 0, val data: QrKeyData = QrKeyData())

@Serializable
data class QrImgData(val qrurl: String = "", val qrimg: String = "")

@Serializable
data class QrImgResp(val code: Int = 0, val data: QrImgData = QrImgData())

@Serializable
data class QrCheckResp(val code: Int = 0, val message: String = "", val cookie: String = "")

@Serializable
data class ArtistDetailResp(
    val code: Int = 0,
    /** `/artist/detail` nests the payload under `data` — `data.artist`. */
    val data: ArtistDetailData = ArtistDetailData(),
    val artist: Artist = Artist(),
    val brief: String = "",
    val followCount: Int = 0,
    val artistId: Long = 0,
    val albumList: List<Album> = emptyList(),
    val songList: List<Song> = emptyList(),
    val playlistList: List<Playlist> = emptyList(),
    val message: String = "",
) {
    /** Whatever envelope the node used, the artist object we want. */
    val resolvedArtist: Artist
        get() = if (artist.name.isBlank() && artist.id == 0L) data.artist else artist

    /** Artist cover is only populated on the explicit detail call. */
    val artistCover: String?
        get() = resolvedArtist.cover ?: albumList.firstOrNull()?.cover
}

@Serializable
data class ArtistDetailData(
    val artist: Artist = Artist(),
    val videoCount: Int = 0,
)

@Serializable
data class ArtistSongsData(val songs: List<Song> = emptyList(), val more: Boolean = false)

/**
 * `/artist/songs` answers `{code, songs, more, total}` — the songs at the top
 * level, not under `data.songs`. Both are read so older nodes still work.
 */
@Serializable
data class ArtistSongsResp(
    val code: Int = 0,
    val songs: List<Song> = emptyList(),
    val data: ArtistSongsData = ArtistSongsData(),
    val more: Boolean = false,
    val total: Int = 0,
    val message: String = "",
) {
    val resolvedSongs: List<Song> get() = if (songs.isEmpty()) data.songs else songs
}

@Serializable
data class ArtistAlbumsResp(val code: Int = 0, val data: List<Album> = emptyList(), val more: Boolean = false)

@Serializable
data class AlbumDetailResp(val code: Int = 0, val album: Album = Album(), val songs: List<Song> = emptyList())

@Serializable
data class SimilarArtistsResp(val code: Int = 0, val data: List<Artist> = emptyList(), val message: String = "")

@Serializable
data class LikelistData(val ids: List<Long> = emptyList(), val songCount: Int = 0)

@Serializable
data class LikelistResp(val code: Int = 0, val data: LikelistData = LikelistData())

@Serializable
data class UserDetailResp(val code: Int = 0, val user: User = User(), val message: String = "")

@Serializable
data class UserSubCountResp(
    val code: Int = 0,
    val profileCount: Int = 0,
    val playlistCount: Int = 0,
    val djCount: Int = 0,
)

@Serializable
data class AvatarUploadResp(val code: Int = 0, val avatarUrl: String = "", val message: String = "")

@Serializable
data class CreatePlaylistResp(val code: Int = 0, val playlistId: Long = 0, val message: String = "")

@Serializable
data class SubscribeResp(val code: Int = 0, val subed: Boolean = false, val message: String = "")

@Serializable
data class SongOrderResp(val code: Int = 0, val message: String = "")

@Serializable
data class DownloadUrlResp(val code: Int = 0, val data: SongUrlData = SongUrlData(), val message: String = "")

@Serializable
data class CheckMusicResp2(val code: Int = 0, val success: Boolean = false, val message: String = "")

@Serializable
data class CloudTokenResp(val code: Int = 0, val data: CloudUploadToken = CloudUploadToken(), val message: String = "")

/**
 * `/user/cloud` pages the drive. `size`/`maxSize` are *strings* upstream even
 * though they are byte counts, so they are parsed defensively at the call site.
 */
@Serializable
data class UserCloudResp(
    val code: Int = 0,
    val count: Int = 0,
    val size: String = "",
    val maxSize: String = "",
    val upgradeSign: Int = 0,
    val hasMore: Boolean = false,
    val data: List<CloudSongItem> = emptyList(),
    val message: String = "",
    val msg: String = "",
)

@Serializable
data class CloudUploadTokenResp(
    val code: Int = 0,
    val data: CloudUploadToken = CloudUploadToken(),
    val message: String = "",
    val msg: String = "",
)

@Serializable
data class CloudUploadCompleteData(val songId: String = "", val code: Int = 0)

@Serializable
data class CloudUploadCompleteResp(
    val code: Int = 0,
    val data: CloudUploadCompleteData = CloudUploadCompleteData(),
    val message: String = "",
    val msg: String = "",
)

@Serializable
data class CloudDelResp(val code: Int = 0, val message: String = "", val msg: String = "")

@Serializable
data class UnblockResp(val code: Int = 0, val data: String = "", val proxyUrl: String = "", val message: String = "")

@Serializable
data class PlayHistoryData(val total: Int = 0, val list: List<RecentSongItem> = emptyList())

/**
 * `/record/recent/song` pages under `data.list`, and each entry is a *wrapper*
 * around the song — the track itself sits in `data`, next to `playTime` and
 * `resourceType`. Decoding it as a bare [Song] silently zeroed every field,
 * because `id`/`name` only exist one level deeper.
 */
@Serializable
data class RecentSongItem(
    val data: Song = Song(),
    val playTime: Long = 0,
    val resourceType: String? = null,
    val banned: Boolean = false,
    val resourceId: String? = null,
)

/** `/record/recent/song` nests the page under `data` (`data.list`). */
@Serializable
data class PlayHistoryResp(
    val code: Int = 0,
    val data: PlayHistoryData = PlayHistoryData(),
    val message: String = "",
)
