package com.yeemusic.android.api

import com.yeemusic.android.settings.SettingsStore
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Typed facade over the NeteaseCloudMusicAPI backend.
 *
 * Endpoint paths and parameter names follow the desktop client's `src/lib/services/`,
 * so behaviour stays consistent with the version you already run on Windows.
 */
class MusicApi(private val client: ApiClient, private val settings: SettingsStore) {

    /**
     * Separate from [ApiClient]'s: this one hits whatever host the mirror handed
     * back, not the configured node, so it must not carry the node's cookie or
     * signing behaviour.
     */
    private val mirrorClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    // == search ============================================================

    suspend fun searchDefault(): SearchDefaultData =
        client.getT<SearchDefaultResp>("/search/default").data

    suspend fun searchSuggest(keywords: String): List<String> =
        client.getT<SearchSuggestResp>("/search/suggest", mapOf("keywords" to keywords, "type" to "mobile"))
            .result.allMatch.map { it.keyword }

    /**
     * type: 1 song · 10 album · 100 artist · 1000 playlist · 1002 user ·
     * 1006 lyric · 1009 radio · 1018 all
     */
    suspend fun search(keywords: String, type: Int = 1, limit: Int = 30, offset: Int = 0): SearchResult =
        client.getT<SearchResp>(
            "/cloudsearch",
            mapOf(
                "keywords" to keywords,
                "limit" to limit.toString(),
                "offset" to offset.toString(),
                "type" to type.toString(),
            ),
        ).result

    // == songs =============================================================

    suspend fun songDetail(ids: List<Long>, offset: Int = 0): List<Song> {
        if (ids.isEmpty()) return emptyList()
        val params = buildMap {
            put("ids", ids.joinToString(","))
            if (offset > 0) put("offset", offset.toString())
        }
        return client.getT<SongDetailResp>("/song/detail", params).songs
    }

    /**
     * Resolves a playable URL. May be null for VIP/lost songs.
     *
     * `unblock` asks the node to run its 解锁 path for fee-1 songs; without it the
     * node answers a VIP track with the 30-second 试听 fragment even when the
     * account is entitled. The node returns the WHOLE track as a FLAC in that case.
     *
     * NOTE: the query parameter is `unblock`, not `unlock` — the node ignores the
     * latter and keeps answering with the 30-second 试听.
     */
    suspend fun songUrl(id: Long, level: String, unblock: Boolean = false): SongUrlData? {
        val resp = client.getT<SongUrlResp>(
            "/song/url/v1",
            buildMap {
                put("id", id.toString())
                put("level", level)
                if (unblock) put("unblock", "true")
            },
        )
        return resp.data.firstOrNull { it.url?.isNotBlank() == true } ?: resp.data.firstOrNull()
    }

    suspend fun songUrlAt(id: Long, level: String): SongUrlData? = songUrl(id, level)

    suspend fun downloadUrl(id: Long, level: String): SongUrlData? {
        val resp = client.getT<DownloadUrlResp>(
            "/song/download/url/v1",
            mapOf("id" to id.toString(), "level" to level),
        )
        return resp.data.takeIf { it.url.isNullOrBlank() == false }
    }

    /**
     * A VIP track the unlocked request could not turn into a URL: `/unblock/music`
     * swaps the id for a mirror copy and hands back its playable URL.
     *
     * The mirror is whatever quality the node can find, so callers should accept
     * that a 解锁 never restores the level that was originally asked for.
     */
    suspend fun unblockedUrl(id: Long, level: String): SongUrlData? =
        runCatching { unblockMusic(id) }.getOrNull()?.let { resp ->
            resp.data.takeIf { it.startsWith("http") }?.let { direct ->
                // Already resolved by the node; the level is a guess, the type is
                // read off the path so the caller does not tag a FLAC as mp3.
                SongUrlData(
                    id = id,
                    url = direct,
                    code = 200,
                    level = level,
                    type = direct.substringBefore('?').substringAfterLast('.', "mp3"),
                    size = headContentLength(direct),
                )
            } ?: SongUrlData(id = id, url = songUrl(id, level, unblock = true)?.url, code = resp.code)
        }

    /**
     * The mirror hands back only a URL, so the playback cache has no size to
     * compare against and can never tell a complete copy from a partial one.
     * A ranged GET answers with the real length; a HEAD is tried first because
     * some CDNs answer it but ignore Range on a GET.
     *
     * Never throws: an unreachable mirror has to stay playable online, and
     * offline detection simply skips it when the length is unknown.
     */
    private suspend fun headContentLength(url: String): Long = withContext(Dispatchers.IO) {
        val head = Request.Builder().url(url).head()
            .header("User-Agent", "YeeMusic-Android/1.0").build()
        headContentLengthOf(head)?.let { return@withContext it }

        val range = Request.Builder().url(url)
            .header("User-Agent", "YeeMusic-Android/1.0")
            .header("Range", "bytes=0-0")
            .build()
        headContentLengthOf(range) ?: 0L
    }

    private fun headContentLengthOf(request: Request): Long? = runCatching {
        mirrorClient.newCall(request).execute().use { response ->
            val header = response.header("Content-Range")?.substringAfterLast('/')
            val length = header?.toLongOrNull()
                ?: response.header("Content-Length")?.toLongOrNull()
            if (response.isSuccessful && (length ?: -1L) > 0) length else null
        }
    }.getOrNull()

    
    suspend fun songLyric(id: Long): LyricData =
        client.getT<LyricData>("/lyric/new", mapOf("id" to id.toString()))

    suspend fun songQuality(id: Long): SongQualityMap =
        client.getT<SongQualityResp>("/song/music/detail", mapOf("id" to id.toString())).data

    suspend fun checkMusic(ids: List<Long>): Boolean {
        val resp = client.postT<CheckMusicResp2>(
            "/check/music",
            mapOf("ids" to ids.joinToString(",")),
        )
        return resp.success
    }

    /** Attempts the "解锁" flow for VIP songs the account is entitled to. */
    suspend fun unblockMusic(id: Long): UnblockResp = client.postT(
        "/unblock/music",
        mapOf("id" to id.toString(), "level" to settings.getQuality()),
    )

    // == recommend / fm ====================================================

    suspend fun dailyRecommend(): List<Song> =
        client.getT<DailyRecommendResp>("/recommend/songs").data.songs

    suspend fun personalFm(): List<Song> {
        val resp = client.getT<RespData<List<Song>>>("/personal_fm")
        val ids = resp.data.orEmpty().map { it.id }
        return songDetail(ids)
    }

    suspend fun fmTrash(id: Long): Boolean =
        client.getT<CodeResp>("/fm_trash", mapOf("id" to id.toString())).code == 200

    // == playlists =========================================================

    suspend fun playlistDetail(playlistId: Long): Playlist {
        val resp = client.getT<PlaylistDetailResp>("/playlist/detail", mapOf("id" to playlistId.toString()))
        requirePlaylistCode(resp.code, resp.message)
        return resp.playlist
    }

    suspend fun playlistTracksAll(playlistId: Long): List<Song> {
        val resp = client.getT<PlaylistTrackAllResp>("/playlist/track/all", mapOf("id" to playlistId.toString()))
        requirePlaylistCode(resp.code, resp.message)
        // `songs` is where the node puts them now; the other two are the legacy
        // locations, kept so an older node still works.
        return resp.songs.ifEmpty { resp.tracks }.ifEmpty { resp.playlist.tracks }
    }

    /**
     * These endpoints answer HTTP 200 with `code: 404` and a message such as
     * "歌单不存在" when the playlist is gone or off-limits. Surfaces that as an
     * error instead of a page that loads forever with nothing in it.
     */
    private fun requirePlaylistCode(code: Int, message: String?) {
        if (code != 200 && (message?.isNotBlank() == true || code != 0)) {
            throw ApiException(message ?: "请求失败：$code")
        }
    }

    suspend fun createPlaylist(name: String): Long {
        val resp = client.postT<CreatePlaylistResp>("/playlist/create", mapOf("name" to name, "type" to "0"))
        require(resp.code == 200) { resp.message ?: "创建歌单失败" }
        return resp.playlistId
    }

    suspend fun updatePlaylist(playlist: Playlist) {
        client.postJson(
            "/playlist/update",
            buildJsonObject { put("playlist", JSON.encodeToJsonElement<Playlist>(playlist)) },
        )
    }

    suspend fun deletePlaylists(ids: List<Long>) {
        val arr = buildJsonArray { ids.forEach { add(it) } }
        client.postJson("/playlist/delete", buildJsonObject { put("ids", arr) })
    }

    /** op: add · del · set */
    suspend fun modifyTracks(playlistId: Long, op: String, trackIds: List<Long>) {
        val arr = buildJsonArray { trackIds.forEach { add(it) } }
        client.postJson(
            "/playlist/tracks",
            buildJsonObject {
                put("plid", playlistId)
                put("op", op)
                put("tracks", arr)
            },
        )
    }

    suspend fun updateTrackOrder(playlistId: Long, trackIds: List<Long>) {
        client.postT<SongOrderResp>(
            "/song/order/update",
            mapOf("id" to playlistId.toString(), "tracks" to trackIds.joinToString("-")),
        )
    }

    suspend fun subscribePlaylist(playlistId: Long) {
        client.postT<SubscribeResp>(
            "/playlist/subscribe",
            mapOf("plid" to playlistId.toString(), "tm" to System.currentTimeMillis().toString()),
        )
    }

    // == artists ===========================================================

    suspend fun artistDetail(id: Long): ArtistDetailResp =
        client.getT<ArtistDetailResp>("/artist/detail", mapOf("id" to id.toString()))

    suspend fun artistSongs(id: Long, offset: Int = 0, limit: Int = 20): List<Song> =
        client.getT<ArtistSongsResp>(
            "/artist/songs",
            mapOf("id" to id.toString(), "offset" to offset.toString(), "limit" to limit.toString()),
        ).resolvedSongs

    suspend fun artistAlbums(id: Long, offset: Int = 0, limit: Int = 30): List<Album> =
        client.getT<ArtistAlbumsResp>(
            "/artist/album",
            mapOf("id" to id.toString(), "offset" to offset.toString(), "limit" to limit.toString()),
        ).data

    suspend fun subscribeArtist(id: Long) {
        client.postT<SubscribeResp>(
            "/artist/sub",
            mapOf("artistId" to id.toString(), "tm" to System.currentTimeMillis().toString()),
        )
    }

    suspend fun similarArtists(id: Long): List<Artist> =
        client.getT<SimilarArtistsResp>("/simi/artist", mapOf("id" to id.toString())).data

    // == albums ============================================================

    suspend fun albumDetail(id: Long): AlbumDetailResp =
        client.getT<AlbumDetailResp>("/album", mapOf("id" to id.toString()))

    suspend fun albumSongs(id: Long, limit: Int = 100, offset: Int = 0): List<Song> =
        client.getT<AlbumDetailResp>(
            "/album",
            mapOf("id" to id.toString(), "limit" to limit.toString(), "offset" to offset.toString()),
        ).songs

    suspend fun subscribeAlbum(id: Long) {
        client.postT<SubscribeResp>("/album/sub", mapOf("id" to id.toString(), "tm" to System.currentTimeMillis().toString()))
    }

    // == user ==============================================================

    suspend fun userDetail(uid: Long): User =
        client.getT<UserDetailResp>("/user/detail", mapOf("uid" to uid.toString())).user

    suspend fun userPlaylists(uid: Long): List<Playlist> =
        client.getT<UserPlaylistResp>("/user/playlist", mapOf("uid" to uid.toString())).playlist

    suspend fun userSubCount(uid: Long): UserSubCountResp =
        client.getT<UserSubCountResp>("/user/subcount", mapOf("uid" to uid.toString()))

    suspend fun likedSongIds(uid: Long): List<Long> {
        val resp = client.getT<LikelistResp>("/likelist", mapOf("uid" to uid.toString()))
        return resp.data.ids
    }

    suspend fun likedSongs(uid: Long): List<Song> = songDetail(likedSongIds(uid))

    /** like=true 收藏, like=false 取消收藏 */
    suspend fun toggleLike(songId: Long, like: Boolean): CodeResp =
        client.postT<CodeResp>("/like", mapOf("id" to songId.toString(), "like" to like.toString()))

    suspend fun updateUser(name: String? = null, signature: String? = null) {
        val params = buildMap {
            name?.let { put("name", it) }
            signature?.let { put("signature", it) }
        }
        if (params.isEmpty()) return
        client.postT<CodeResp>("/user/update", params)
    }

    suspend fun avatarUploadUrl(uid: Long): String? =
        client.getT<AvatarUploadResp>("/avatar/upload", mapOf("uid" to uid.toString())).avatarUrl
            .takeIf { it.isNotBlank() }

    // == auth ==============================================================

    suspend fun qrKey(): QrKeyData = client.getT<QrKeyResp>("/login/qr/key").data

    suspend fun qrImage(key: String): QrImgData =
        client.getT<QrImgResp>("/login/qr/create", mapOf("key" to key, "qrimg" to "true")).data

    /** code 803 = success and the cookie is returned; 801 pending, 802 expired. */
    suspend fun qrCheck(key: String): QrCheckResp =
        client.getT<QrCheckResp>("/login/qr/check", mapOf("key" to key))

    suspend fun sendCaptcha(phone: String): CodeResp =
        client.getT<CodeResp>("/captcha/sent", mapOf("phone" to phone))

    suspend fun verifyCaptcha(phone: String, captcha: String): CodeResp =
        client.getT<CodeResp>("/captcha/verify", mapOf("phone" to phone, "captcha" to captcha))

    suspend fun loginByPhone(phone: String, captcha: String): LoginResp =
        client.postT<LoginResp>("/login/cellphone", mapOf("phone" to phone, "captcha" to captcha))

    suspend fun refreshLogin(): RefreshResp = client.getT<RefreshResp>("/login/refresh")

    suspend fun loginStatus(): LoginStatusData = client.getT<LoginStatusResp>("/login/status").data

    suspend fun logout() {
        runCatching { client.getT<CodeResp>("/logout") }
    }

    // == history ===========================================================

    /**
     * Each `data.list` entry wraps the track in `data`; see [RecentSongItem].
     * Entries with a blank or zeroed `id` are dropped — they are unplayable,
     * and a repeated key is what took the whole process down.
     */
    suspend fun recentSongs(limit: Int = 100): List<Song> =
        client.getT<PlayHistoryResp>("/record/recent/song", mapOf("limit" to limit.toString()))
            .data.list
            .map { it.data }
            .distinctBy { it.id }
            .filter { it.id != 0L && it.name.isNotBlank() }

    suspend fun recentAlbums(): List<Album> {
        val resp = client.getT<JsonElement>("/record/recent/album")
        return try {
            val obj = resp as? JsonObject ?: return emptyList()
            (obj["list"] as? JsonArray)?.mapNotNull { runCatching { it.decode<Album>() }.getOrNull() }.orEmpty()
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun recentPlaylists(): List<Playlist> {
        val resp = client.getT<JsonElement>("/record/recent/playlist")
        return try {
            val obj = resp as? JsonObject ?: return emptyList()
            (obj["list"] as? JsonArray)?.mapNotNull { runCatching { it.decode<Playlist>() }.getOrNull() }.orEmpty()
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun recentListenList(): RecentListenData? =
        client.getT<RecentListenResp>("/recent/listen/list").data

    // == homepage ==========================================================

    /** Filters out the blocks the desktop app hides. */
    suspend fun homepageBlocks(refresh: Boolean = false): List<HomepageBlock> {
        val resp = client.getT<HomepageResp>("/homepage/block/page", mapOf("refresh" to refresh.toString()))
        return resp.data?.blocks.orEmpty().filter { block ->
            block.blockCode != "HOMEPAGE_BLOCK_OLD_DRAGON_BALL" && block.blockCode != "HOMEPAGE_BANNER"
        }
    }

    // == cloud drive =======================================================

    /**
     * One page of the drive. `uid` is not part of the real contract — the drive
     * belongs to whoever the cookie says — so it is deliberately not sent.
     */
    suspend fun cloudPage(limit: Int = 200, offset: Int = 0): UserCloudResp =
        client.getT(
            "/user/cloud",
            mapOf("limit" to limit.toString(), "offset" to offset.toString()),
        )

    /**
     * Walks [cloudPage] to the end.
     *
     * `hasMore` is honoured, but the loop also stops on a short page: some nodes
     * answer `hasMore: false` on the very first request while still paging
     * internally, which would silently truncate the list.
     */
    suspend fun cloudAll(): UserCloudData {
        val items = mutableListOf<CloudSongItem>()
        var count = 0
        var used = 0L
        var total = 0L
        var offset = 0
        while (true) {
            val page = cloudPage(limit = 200, offset = offset)
            // 301 + "需要登录" is how this node reports an expired cookie. Left
            // alone it reads as an empty drive, which is the wrong diagnosis.
            if (page.code == 301) throw ApiException(page.message?.ifBlank { page.msg } ?: "需要登录")
            if (page.code != 200 && items.isEmpty()) {
                throw ApiException(page.message?.ifBlank { page.msg } ?: "获取云盘失败：${page.code}")
            }
            if (page.data.isEmpty()) break
            items += page.data
            count = page.count
            used = page.size.toLongOrNull() ?: used
            total = page.maxSize.toLongOrNull() ?: total
            offset += page.data.size
            if (!page.hasMore) break
        }
        return UserCloudData(
            items = items,
            count = if (count > 0) count else items.size,
            usedBytes = used,
            totalBytes = total,
        )
    }

    /** Deletes one or more tracks from the drive. */
    suspend fun cloudDelete(songIds: List<Long>) {
        if (songIds.isEmpty()) return
        // The node takes a single `id` holding a comma-separated list.
        val resp = client.getT<CloudDelResp>("/user/cloud/del", mapOf("id" to songIds.joinToString(",")))
        if (resp.code != 200 && resp.code != 0) {
            throw ApiException(resp.message?.ifBlank { resp.msg } ?: "删除失败：${resp.code}")
        }
    }

    /**
     * Exchange for upload credentials.
     *
     * `md5` is required and the node will reject the call without it, so callers
     * must hash the file first. When the same bytes are already in the drive the
     * answer comes back `needUpload: false` — a fast-path "秒传".
     */
    suspend fun cloudUploadToken(
        md5: String,
        fileSize: Long,
        filename: String,
        bitrate: Int = 999000,
    ): CloudUploadToken {
        val resp = client.postT<CloudUploadTokenResp>(
            "/cloud/upload/token",
            mapOf(
                "md5" to md5,
                "fileSize" to fileSize.toString(),
                "filename" to filename,
                "bitrate" to bitrate.toString(),
            ),
        )
        if (resp.code != 200 || resp.data.uploadUrl.isBlank()) {
            throw ApiException(resp.msg?.ifBlank { resp.message } ?: "获取上传凭证失败：${resp.code}")
        }
        return resp.data
    }

    /**
     * Archives the upload.
     *
     * The node needs the credentials' `songId`/`resourceId` (not the local
     * file's), so those have to be carried over from [cloudUploadToken].
     */
    suspend fun cloudUploadComplete(
        songId: String,
        resourceId: String,
        md5: String,
        filename: String,
        song: String? = null,
        artist: String? = null,
        album: String? = null,
    ) {
        val resp = client.postT<CloudUploadCompleteResp>(
            "/cloud/upload/complete",
            buildMap {
                put("songId", songId)
                put("resourceId", resourceId)
                put("md5", md5)
                put("filename", filename)
                song?.let { put("song", it) }
                artist?.let { put("artist", it) }
                album?.let { put("album", it) }
            },
        )
        if (resp.code != 200) {
            throw ApiException(resp.msg?.ifBlank { resp.message } ?: "导入云盘失败：${resp.code}")
        }
    }

    // == helpers ===========================================================

    /** Best-effort: fill in missing artist/album info for sparse song objects. */
    suspend fun enrich(songs: List<Song>): List<Song> = songDetail(songs.map { it.id })
}
