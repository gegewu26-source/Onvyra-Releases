package com.constx.onvyra.ui.pages.netdisk

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.constx.onvyra.data.netdisk.NetDiskException
import com.constx.onvyra.data.netdisk.NetDiskPlatform
import com.constx.onvyra.data.netdisk.auth.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Every native field submits through its provider's real protocol, never a generic DOM filler. */
@Composable
internal fun NetDiskNativeLoginContent(platform: NetDiskPlatform, onCredential: suspend (String) -> Unit,
    onAdvanced: () -> Unit, onOfficial: () -> Unit) {
    if (platform == NetDiskPlatform.ALIYUN) {
        NetDiskAliyunLoginContent(onCredential, onOfficial, onAdvanced)
        return
    }
    if (!NetDiskLoginPolicy.supportsNativePassword(platform)) {
        LaunchedEffect(platform) { onOfficial() }
        return
    }
    val scope = rememberCoroutineScope()
    val session = remember(platform) { NativeLoginSession(platform) }
    val supportsSms = NetDiskLoginPolicy.supportsNativeSms(platform)
    var account by remember(platform) { mutableStateOf("") }
    var secret by remember(platform) { mutableStateOf("") }
    var sms by remember(platform) { mutableStateOf(false) }
    var visible by remember(platform) { mutableStateOf(false) }
    var consent by remember(platform) { mutableStateOf(false) }
    var busy by remember(platform) { mutableStateOf(false) }
    var sending by remember(platform) { mutableStateOf(false) }
    var codeSent by remember(platform) { mutableStateOf(false) }
    var seconds by remember(platform) { mutableIntStateOf(0) }
    var message by remember(platform) { mutableStateOf<String?>(null) }
    var needsOfficial by remember(platform) { mutableStateOf(false) }
    LaunchedEffect(seconds) { if (seconds > 0) { delay(1_000); seconds-- } }
    DisposableEffect(platform) { onDispose { secret = ""; session.clearSms() } }
    fun handle(error: NetDiskException) {
        message = error.message
        needsOfficial = error.reason in setOf("verification_required", "account_not_found")
        if (error.reason == "sms_required" && supportsSms) { sms = true; secret = "" }
        if (error.reason == "sms_expired") { codeSent = false; session.clearSms(); seconds = 0 }
    }
    LazyColumn(Modifier.fillMaxSize().testTag("netdisk-native-login"),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        item {
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(25.dp),
                border = BorderStroke(0.7.dp, MaterialTheme.colorScheme.outlineVariant), shadowElevation = 2.dp) {
                Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    NetDiskProviderBadge(platform, Modifier.size(56.dp))
                    Text("登录${platform.label}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    if (supportsSms) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !sms, onClick = { sms = false; secret = ""; message = null; needsOfficial = false },
                            enabled = !busy, label = { Text("密码登录") }, modifier = Modifier.weight(1f).testTag("netdisk-mode-password"))
                        FilterChip(selected = sms, onClick = { sms = true; secret = ""; message = null; needsOfficial = false },
                            enabled = !busy, label = { Text("验证码登录") }, modifier = Modifier.weight(1f).testTag("netdisk-mode-sms"))
                    }
                    TextField(account, { value ->
                        account = value.take(254); codeSent = false; seconds = 0; session.clearSms(); message = null
                    }, Modifier.fillMaxWidth().testTag("netdisk-login-account"),
                        placeholder = { Text(if (sms) "手机号" else "账号 / 手机号 / 邮箱") }, singleLine = true, enabled = !busy,
                        keyboardOptions = KeyboardOptions(keyboardType = if (sms) KeyboardType.Phone else KeyboardType.Text))
                    TextField(secret, { secret = it.take(if (sms) 8 else 256) },
                        Modifier.fillMaxWidth().testTag("netdisk-login-secret"),
                        placeholder = { Text(if (sms) "短信验证码" else "密码") }, singleLine = true, enabled = !busy,
                        visualTransformation = if (sms || visible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = if (sms) KeyboardType.NumberPassword else KeyboardType.Password),
                        trailingIcon = {
                            if (sms) TextButton(onClick = {
                                if (!busy) scope.launch {
                                    busy = true; sending = true; message = null; needsOfficial = false
                                    try {
                                        seconds = session.sendSms(account).coerceIn(1, 60)
                                        codeSent = true; message = "验证码已发送，请在有效期内输入。"
                                    } catch (cancelled: CancellationException) { throw cancelled }
                                    catch (error: NetDiskException) { handle(error) }
                                    catch (_: Exception) { message = "验证码未能发送，请稍后重试。" }
                                    finally { sending = false; busy = false }
                                }
                            }, enabled = !busy && account.isNotBlank() && consent && seconds == 0,
                                modifier = Modifier.testTag("netdisk-native-send-code")) {
                                Text(if (seconds > 0) "${seconds}秒" else "获取验证码")
                            } else TextButton(onClick = { visible = !visible }) { Text(if (visible) "隐藏" else "显示") }
                        })
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(consent, { consent = it }, enabled = !busy)
                        Text("我已阅读并同意${platform.label}的服务协议和隐私政策", style = MaterialTheme.typography.bodySmall)
                    }
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    message?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (needsOfficial) FilledTonalButton(onClick = { secret = ""; onOfficial() }, enabled = !busy,
                        modifier = Modifier.fillMaxWidth().testTag("netdisk-complete-verification")) { Text("完成官方验证") }
                    Button(onClick = {
                        if (!busy) scope.launch {
                            busy = true; message = null; needsOfficial = false
                            try {
                                val token = if (sms) session.loginSms(account, secret) else session.login(account, secret)
                                onCredential(token); secret = ""; session.clearSms()
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (error: NetDiskException) { handle(error) }
                            catch (_: Exception) { message = "登录未完成，请稍后重试。" }
                            finally { busy = false }
                        }
                    }, enabled = !busy && account.isNotBlank() && secret.isNotEmpty() && consent && (!sms || codeSent),
                        modifier = Modifier.fillMaxWidth().height(48.dp).testTag("netdisk-native-login-submit"),
                        shape = RoundedCornerShape(16.dp)) { Text(if (busy) { if (sending) "发送中…" else "登录中…" } else "登录") }
                    TextButton(onClick = { secret = ""; onOfficial() }, enabled = !busy,
                        modifier = Modifier.testTag("netdisk-official-login")) {
                        Text(if (platform == NetDiskPlatform.PAN123) "验证码／其他方式" else "其他登录方式")
                    }
                    TextButton(onClick = onAdvanced, enabled = !busy) { Text("凭据管理") }
                }
            }
        }
    }
}

/** One client and verification context survive from SMS send to submit. */
private class NativeLoginSession(private val platform: NetDiskPlatform) {
    private val pan123 by lazy { NetDiskPasswordAuth() }
    private val lanzou by lazy { LanzouNativeAuth() }
    private val ilanzou by lazy { IlanzouNativeAuth() }
    private val xunlei by lazy { XunleiNativeAuth() }
    private val guangya by lazy { GuangyaNativeAuth() }
    private var xunleiChallenge: XunleiSmsChallenge? = null
    private var guangyaChallenge: GuangyaSmsChallenge? = null
    fun clearSms() { xunleiChallenge = null; guangyaChallenge = null }
    suspend fun login(account: String, password: String): String = when (platform) {
        NetDiskPlatform.PAN123 -> pan123.login123(account, password)
        NetDiskPlatform.LANZOU -> lanzou.login(account, password)
        NetDiskPlatform.ILANZOU -> ilanzou.login(account, password)
        NetDiskPlatform.XUNLEI -> xunlei.login(account, password)
        NetDiskPlatform.GUANGYA -> guangya.login(account, password)
        else -> throw NetDiskException("verification_required", "请使用该平台的官方登录组件。")
    }
    suspend fun sendSms(phone: String): Int = when (platform) {
        NetDiskPlatform.XUNLEI -> xunlei.sendSms(phone).also { xunleiChallenge = it }.expiresIn
        NetDiskPlatform.GUANGYA -> guangya.sendSms(phone).also { guangyaChallenge = it }.expiresIn
        else -> throw NetDiskException("verification_required", "请使用该平台的官方验证码登录。")
    }
    suspend fun loginSms(phone: String, code: String): String = when (platform) {
        NetDiskPlatform.XUNLEI -> xunlei.loginSms(phone, code, xunleiChallenge ?: missingSms())
        NetDiskPlatform.GUANGYA -> guangya.loginSms(phone, code, guangyaChallenge ?: missingSms())
        else -> throw NetDiskException("verification_required", "请使用该平台的官方验证码登录。")
    }
    private fun missingSms(): Nothing = throw NetDiskException("sms_expired", "请先获取短信验证码。")
}
