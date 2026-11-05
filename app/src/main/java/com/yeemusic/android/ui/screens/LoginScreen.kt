package com.yeemusic.android.ui.screens

import android.util.Base64
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.yeemusic.android.api.ApiException
import com.yeemusic.android.api.LoginResp
import com.yeemusic.android.api.QrCheckResp
import com.yeemusic.android.api.QrImgData
import com.yeemusic.android.ui.AppActions
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * NetEase login. Two paths, same as the desktop client:
 *  * QR — `login/qr/key` → `login/qr/create` → poll `login/qr/check`
 *  * phone — `captcha/sent` → `captcha/verify` → `login/cellphone`
 *
 * The two paths use different success codes, which is easy to mix up:
 * `/login/qr/check` answers 801 (waiting) / 800 or 802 (expired) / 803
 * (scanned, cookie returned), while `/login/cellphone` answers 200 with the
 * cookie, and never 803. Checking for 803 on the phone path makes every login
 * look like a failure, cookie included.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(actions: AppActions, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var mode by rememberSaveable { mutableStateOf(0) }
    val colors = MaterialTheme.colorScheme

    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    // Either a ByteArray (a base64 data URI, decoded here — see
    // qrImageModel) or a String URL. Both are valid Coil models.
    var qrModel by remember { mutableStateOf<Any?>(null) }
    var qrState by remember { mutableStateOf("正在生成二维码") }
    var pollJob by remember { mutableStateOf<Job?>(null) }

    var phone by remember { mutableStateOf("") }
    var captcha by remember { mutableStateOf("") }
    var captchaSentAt by remember { mutableStateOf(0L) }

    DisposableEffect(Unit) {
        onDispose { pollJob?.cancel() }
    }

    suspend fun applyLogin(cookie: String, hint: String) {
        message = hint
        // The upstream smears Set-Cookie attributes into the body; strip them.
        val clean = actions.apiClient.sanitizeCookie(cookie)
        // Commit the login as *successful* first: the login call returned a
        // cookie, so it worked. /login/status is only a uid lookup on top of
        // that. Folding it in backwards (status 0 first, then overwriting on
        // success) means any hiccup there — timeout, an account:null body, a
        // decode error — silently leaves login_status at 0 and the whole app
        // still reads as logged out, which looks exactly like "登不上".
        runCatching { actions.settings.setAuth(clean, 0L, 200) }
        runCatching { actions.musicApi.loginStatus() }.fold(
            onSuccess = { status ->
                runCatching { actions.settings.setAuth(clean, status.account?.id ?: 0L, 200) }
            },
            onFailure = { },
        )
        actions.refreshLiked()
        onDone()
    }

    fun startQrPolling(key: String) {
        pollJob?.cancel()
        pollJob = scope.launch {
            var elapsed = 0
            while (isActive) {
                delay(3_000)
                elapsed += 3
                when {
                    elapsed > 120 -> {
                        qrState = "二维码已过期，请刷新"
                        pollJob?.cancel()
                        break
                    }
                    else -> {
                        val resp: QrCheckResp? = runCatching { actions.musicApi.qrCheck(key) }.getOrNull()
                        when (resp?.code) {
                            803 -> {
                                qrState = "登录成功"
                                val cookie = resp.cookie
                                if (cookie.isNotBlank()) {
                                    applyLogin(cookie, "扫码登录成功")
                                    return@launch
                                }
                            }
                            800, 802 -> {
                                // 800 is the code this node uses for "expired";
                                // older releases use 802. Accept both.
                                qrState = "二维码已过期，请刷新"
                                pollJob?.cancel()
                                break
                            }
                            801 -> {
                                qrState = "请使用网易云音乐 App 扫码 (${elapsed}s)"
                            }
                            else -> {
                                qrState = "等待扫码 (${elapsed}s)"
                            }
                        }
                    }
                }
            }
        }
    }

    fun requestQr() {
        busy = true
        message = null
        scope.launch {
            try {
                val key = actions.musicApi.qrKey().unikey
                if (key.isBlank()) {
                    message = "获取二维码失败：未返回 unikey"
                    return@launch
                }
                val img = actions.musicApi.qrImage(key)
                qrModel = qrImageModel(img)
                qrState = if (qrModel == null) "二维码图片为空" else "请使用网易云音乐 App 扫码"
                startQrPolling(key)
            } catch (e: Exception) {
                message = e.message ?: "生成二维码失败"
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(Unit) { requestQr() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("登录", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.background),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                listOf("扫码登录", "手机号登录").forEachIndexed { index, label ->
                    Surface(
                        onClick = {
                            if (mode != index) {
                                pollJob?.cancel()
                                mode = index
                            }
                        },
                        shape = RoundedCornerShape(10.dp),
                        color = if (mode == index) colors.primaryContainer else colors.surfaceContainer,
                        modifier = Modifier.weight(1f).padding(horizontal = 4.dp).clickable {},
                    ) {
                        Text(
                            label,
                            modifier = Modifier.padding(vertical = 12.dp),
                            color = if (mode == index) colors.onPrimaryContainer else colors.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(20.dp))

            if (mode == 0) {
                // -- QR ------------------------------------------------------
                if (busy) {
                    Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    Surface(
                        modifier = Modifier.size(224.dp),
                        shape = RoundedCornerShape(16.dp),
                        color = colors.surface,
                    ) {
                        val model = qrModel
                        if (model == null) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("二维码生成失败", color = colors.onSurfaceVariant)
                            }
                        } else {
                            AsyncImage(
                                model = model,
                                contentDescription = "登录二维码",
                                modifier = Modifier.fillMaxSize().padding(14.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text(qrState, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
                OutlinedButton(onClick = { requestQr() }, enabled = !busy) {
                    Icon(Icons.Filled.Refresh, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("刷新二维码")
                }
            } else {
                // -- phone ---------------------------------------------------
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it.filter { c -> c.isDigit() }.take(11) },
                    label = { Text("手机号") },
                    placeholder = { Text("请输入 11 位手机号") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Next),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = captcha,
                    onValueChange = { captcha = it.take(10) },
                    label = { Text("验证码") },
                    placeholder = { Text("4 位验证码") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    if (captchaSentAt > 0L) "验证码已发送到 ${phone.takeLast(4)}，若未收到请重发"
                    else "输入手机号后点击发送验证码",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = {
                            if (phone.length != 11) {
                                message = "请输入正确的 11 位手机号"
                                return@OutlinedButton
                            }
                            scope.launch {
                                message = null
                                runCatching { actions.musicApi.sendCaptcha(phone) }.fold(
                                    onSuccess = { sent ->
                                        // The node answers code 400 when it refuses to
                                        // send; a transport failure is not the only way
                                        // to get no SMS, so don't announce success on
                                        // a mere 2xx response.
                                        if (sent.code == 200) {
                                            captchaSentAt = System.currentTimeMillis()
                                            message = "验证码已发送"
                                        } else {
                                            message = sent.message ?: sent.msg ?: "验证码发送失败"
                                        }
                                    },
                                    onFailure = { message = it.message ?: "验证码发送失败" },
                                )
                            }
                        },
                        enabled = phone.length == 11 && !busy,
                    ) { Text("发送验证码") }
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        onClick = {
                            if (phone.length != 11 || captcha.length != 4) {
                                message = "请输入手机号和 4 位验证码"
                                return@TextButton
                            }
                            busy = true
                            message = null
                            scope.launch {
                                val login: Result<LoginResp> = runCatching {
                                    actions.musicApi.verifyCaptcha(phone, captcha)
                                }.fold(
                                    onSuccess = { checked ->
                                        // /captcha/verify answers 503 for a wrong or
                                        // expired code. Only 200 means the code was
                                        // accepted, so don't spend a login attempt on a
                                        // code we already know the server rejects.
                                        if (checked.code == 200) {
                                            runCatching { actions.musicApi.loginByPhone(phone, captcha) }
                                        } else {
                                            Result.failure(
                                                ApiException(checked.message ?: checked.msg ?: "验证码不正确"),
                                            )
                                        }
                                    },
                                    onFailure = { Result.failure(it) },
                                )
                                login.fold(
                                    onSuccess = { resp ->
                                        busy = false
                                        // /login/cellphone answers code 200 on success —
                                        // 803 is the /login/qr/check convention and never
                                        // comes back here. 803 is still accepted so a
                                        // differently-behaving node does not break either.
                                        if (resp.code == 200 || resp.code == 803) {
                                            if (resp.cookie.isNotBlank()) {
                                                applyLogin(resp.cookie, "登录成功")
                                            } else {
                                                message = "登录失败：${resp.message ?: resp.msg ?: "未返回 cookie"}"
                                            }
                                        } else {
                                            message = "登录失败：${resp.message ?: resp.msg ?: "code=${resp.code}"}"
                                        }
                                    },
                                    onFailure = {
                                        busy = false
                                        message = it.message ?: "登录失败"
                                    },
                                )
                            }
                        },
                        enabled = !busy,
                    ) {
                        Text(if (busy) "登录中…" else "登录")
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            message?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.error,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                "登录后即可同步收藏、创建歌单并获取更高音质。",
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 16.dp),
            )
        }
    }
}

/**
 * Resolves the QR payload to something Coil can actually draw.
 *
 * The node hands `qrimg` back as a `data:image/png;base64,...` URI rather than a
 * URL (the desktop client is unaffected because an `<img src>` takes a data URI
 * natively). Coil cannot fetch it: a `String` model is mapped to an
 * `android.net.Uri` and no fetcher in the bundled artifact claims the `data`
 * scheme — the APK's dex contains no `DataUriFetcher` and no base64 data-URI
 * handler at all. Decoding to a `ByteArray` here works, because `ByteArray` is
 * a first-class Coil model (`ByteArrayMapper` → `ByteBufferFetcher`).
 *
 * Anything that is not a base64 data URI is returned untouched as a URL.
 */
private fun qrImageModel(qr: QrImgData): Any? {
    val raw = qr.qrimg.ifBlank { qr.qrurl }
    if (raw.isBlank()) return null
    return parseDataUriBytes(raw) ?: raw
}

/** Decodes a `data:<media>;base64,<payload>` URI, or null if [uri] is not one. */
private fun parseDataUriBytes(uri: String): ByteArray? {
    val comma = uri.indexOf(',')
    if (comma <= 0) return null
    if ("base64" !in uri.substring(0, comma).lowercase()) return null
    val payload = uri.substring(comma + 1).filterNot { it.isWhitespace() }
    if (payload.isEmpty()) return null
    val padded = payload + "=".repeat((4 - payload.length % 4) % 4)
    return try {
        Base64.decode(padded, Base64.NO_WRAP).takeIf { it.isNotEmpty() }
    } catch (e: IllegalArgumentException) {
        null
    }
}
