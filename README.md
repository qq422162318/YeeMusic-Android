# YeeMusic Android

网易云音乐第三方客户端的 Android 版本，由 [YeeMusic](https://github.com/daidaidai7879/YeeMusic) 桌面版（React + Tauri）移植而来。
纯 Kotlin + Jetpack Compose，没有 WebView，没有 Electron。

## 为什么有这个项目

桌面版靠 Tauri 起一个浏览器壳。移动端不需要，也不需要：直接调后端接口、直接放音频。
请求契约和桌面版完全一致 —— `timestamp` 和 `cookie` 在 GET/DELETE 上走 query，在 POST/PUT 上合并进 JSON body。
这点看起来是小事，但和桌面版行为一致是本项目的核心。

## 功能

| 模块 | 实现情况 |
| --- | --- |
| 搜索 | 单曲 / 歌单 / 歌手 / 专辑 四类分页，热门搜索词 |
| 播放 | 播放队列、顺序/列表循环/单曲循环/随机、上一首/下一首、±15s、进度拖拽、音量 |
| 播放器 | 全屏播放页 + 逐行滚动歌词（支持翻译/罗马音）、拖拽下滑关闭 |
| 首页 | 每日推荐、私人电台、最近播放 |
| 歌单 | 歌单详情、创建歌单、订阅、删除 |
| 歌手 / 专辑 | 详情、歌曲分页、专辑曲目 |
| 我的 | 用户详情、喜欢歌曲、最近播放、我创建的歌单、编辑昵称签名 |
| 账号 | 扫码登录（`login/qr/key` → `create` → `check` 轮询，803 成功）、手机验证码登录 |
| 下载 | 离线下载到应用私有目录、缓存上限、清理下载/播放缓存 |
| 本地音乐 | SAF 选择器 + 持久化授权，无需存储权限 |
| 后台播放 | Media3 MediaSession 前台服务、锁屏/通知栏控制、封面 |
| 设置 | API 地址（可切换节点）、音质、主题、歌词字号、缓存上限、重置 |

## 构建

```bash
# Android Studio: 直接打开目录，Sync 后构建。
# 命令行:
./gradlew assembleDebug
./gradlew assembleRelease
```

`gradlew`、`gradle-wrapper.jar` 和 `gradle-wrapper.properties`（8.9）都在仓库里。

### 替换 API 节点

默认节点在 `app/build.gradle.kts`：

```kotlin
val apiBaseUrl = (findProperty("apiBaseUrl") as? String)
    ?: (findProperty("VITE_API_BASE_URL") as? String)
    ?: "http://116.205.124.216:4000"
```

编译时覆盖：

```bash
./gradlew assembleDebug -PapiBaseUrl=http://your-ncm-api.example.com:4000
```

运行时也可以在 设置 → 网络 里改，并当场测试连通性 —— 不需要重新编译。

### 明文 HTTP（重要）

`NetworkSecurityConfig` 只在进程启动时读取一次，Android 没有提供任何运行时修改的 API，
所以**明文放行名单只能在编译时确定**。想让它对某个远端节点生效，必须重新编译。

策略由 `app/build.gradle.kts` 生成到
`app/build/generated/networkconfig/xml/network_security_config.xml`
（`src/main/res/xml/` 下的同名文件已删除，避免两份冲突）。基线是 HTTPS-only，
额外放行的有：

- loopback / 模拟器：`localhost`、`127.0.0.1`、`10.0.2.2`
- 私有网段：`192.168.0.0/16`、`172.16.0.0/12`、`169.254.0.0/16`
- `-PapiBaseUrl` 指向的 `http://` 节点本身
- `-PcleartextHosts` 里额外列出的主机 / IP
- **音源 CDN 域名**：`music.126.net`、`music.126.com`、`music.163.com`、`tsdm.cn`、`tfdtb.com`
  （`-PmediaHosts` 覆盖默认值）

```bash
# 再放行两个节点（运行时切换到它们也能通）
./gradlew assembleDebug -PcleartextHosts=api.example.com,192.168.1.10

# 换了音源域名（比如自建代理转发）时补上
./gradlew assembleDebug -PmediaHosts=music.126.net,my-mirror.example.com
```

默认节点是无 TLS 的 HTTP，所以它的 IP 会被自动加入放行名单；
设置 → 网络 里有一段只读提示，会告诉你当前填的地址到底能不能连。

⚠️ 只放行节点、不放行音源域名的话，**每一首歌都播不出来**，界面上只显示
`播放出错：ERROR_CODE_IO_UNSPECIFIED`：`/song/url` 给的是 `http://m*.music.126.net/...`
这类纯 HTTP 地址，Android 在 socket 层就拒绝了连接，ExoPlayer 拿到的只是
一个没有分类的 I/O 异常。桌面版和 Termux 里的 curl 都不会报这个错
（它们没有这套策略），所以只有装到 Android 上才会暴露。


## 架构

```
app/src/main/java/com/yeemusic/android/
├── YeeMusicApplication.kt      # 进程级单例：settings / apiClient / musicApi / downloads / player
├── MainActivity.kt             # 单 Activity 壳：底部导航 + MiniPlayer + NavHost
├── api/
│   ├── ApiClient.kt            # OkHttp；复刻桌面版的请求契约
│   ├── Models.kt               # 线上模型（全部可空/有默认值，API 松耦合）
│   ├── Responses.kt            # 各端点的响应信封
│   └── MusicApi.kt             # 类型化门面，60+ 端点
├── settings/SettingsStore.kt   # DataStore：API 地址、cookie、音质、缓存上限…
├── player/
│   ├── PlayerManager.kt        # ExoPlayer + MediaSession + 队列
│   ├── PlaybackService.kt      # MediaSessionService，不持有任何状态
│   ├── LyricParser.kt          # LRC 解析 + 二分查找当前行
│   └── Storage.kt              # 下载 + 流式缓存
└── ui/
    ├── theme/  nav/  components/   # 主题、路由、通用组件
    ├── AppActions.kt                     # 屏幕共用的操作集合
    └── screens/                          # 首页/搜索/我的/播放/下载/本地/设置/登录/账号
```

**单例归属**：`PlayerManager` 和 `MediaSession` 都活在 Application 里，`PlaybackService` 不复制任何状态，
只是把同一个 session `setActive(true)`。这样锁屏、通知栏和界面描述的是同一个对象。

**队列是一个 `List<Song>`，不是 URL 列表**。后端给的播放地址是短时效且分音质的，
所以每次播放时才解析地址：本地下载 > `song.uri`/`song.path`（本地音乐）> `songUrl()`（按当前音质，失败降级 standard）。

**播放队列推进**：ExoPlayer 一次只挂一个 MediaSource，自然播完不会自动切歌，
所以 `onMediaItemTransition(AUTO)` 里自己推进 index，单曲循环/随机也在这里分派。

## 与桌面版的差异

- 没有歌词面板切换的独立路由，歌词内嵌在全屏播放器里。
- 没有电台/数字专辑/歌单编辑页的完整实现（只有创建、订阅、删除）。
- 没有网页版播放地址（`web/url`），走 `/song/url/v1`。
- 首页只取每日推荐/私人电台/最近播放，`/homepage/block/page` 的区块墙未做。
- 云盘上传（`cloud/upload/token`）接口已实现但未接 UI。
- 桌面版的歌词皮肤、播放模式快捷键等 UI 细节没有搬过来。

## 许可

YeeMusic 是 **AGPL-3.0**。派生的 Android 应用同样需要按 AGPL-3.0 发布源码。
音乐数据和版权属于网易云音乐及相关权利人，本应用仅供个人学习和交流，请勿用于商业用途。

## 状态

**已编译通过**（`./gradlew assembleDebug`，AGP 8.7.3 / Kotlin 2.0.21 / compileSdk 35）。
目前只有一个弃用警告：`PlayerManager.kt` 里 `SimpleCache(File, CacheEvictor)` 构造函数已过时。

已按 `http://116.205.124.216:4000` 节点验证过的端点：`/search/default`、`/search/suggest`、
`/recommend/songs`、`/personal_fm`、`/lyric/new`、`/song/url/v1`、`/login/qr/key` + `create`。
