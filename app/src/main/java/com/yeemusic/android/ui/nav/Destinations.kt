package com.yeemusic.android.ui.nav

/** Route ids for the Compose nav graph. */
object Routes {
    const val HOME = "home"
    const val SEARCH = "search"
    const val LIBRARY = "library"
    const val DOWNLOADS = "downloads"
    const val LOCAL = "local"
    const val CLOUD = "cloud"
    const val SETTINGS = "settings"
    const val PROFILE = "profile"
    const val LOGIN = "login"
    const val FULL_PLAYER = "player"
    const val PLAYLIST = "playlist/{id}"
    const val ARTIST = "artist/{id}"
    const val ALBUM = "album/{id}"

    fun playlist(id: Long) = "playlist/$id"
    fun artist(id: Long) = "artist/$id"
    fun album(id: Long) = "album/$id"

    /** The four bottom-navigation destinations. */
    val bottomNav: List<Tab> = listOf(
        Tab(HOME, "首页"),
        Tab(SEARCH, "搜索"),
        Tab(LIBRARY, "我的"),
        Tab(PROFILE, "账号"),
    )
}

data class Tab(val route: String, val label: String)
