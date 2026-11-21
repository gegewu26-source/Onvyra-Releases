package me.rerere.androidcontrol.shell

import me.rerere.androidcontrol.adb.AdbConnectionException
import me.rerere.androidcontrol.adb.AdbIoCancelledException
import me.rerere.androidcontrol.adb.AdbPairingException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeoutException
import javax.net.ssl.SSLException
import kotlinx.coroutines.TimeoutCancellationException

/** Only fixed descriptions and exception class names are exposed; raw messages may contain secrets. */
internal fun androidShellFailureMessage(failure: Throwable): String {
    val chain = generateSequence(failure) { it.cause?.takeIf { next -> next !== it } }.take(8).toList()
    val pairing = chain.filterIsInstance<AdbPairingException>().firstOrNull()
    val connection = chain.filterIsInstance<AdbConnectionException>().firstOrNull()
    val root = chain.filterIsInstance<RootShellStartException>().firstOrNull()
    val description = when {
        chain.any { it is AdbIoCancelledException } -> "已取消当前配对或连接，现有配对身份未撤销"
        root != null -> when (root.reason) {
            RootShellStartException.Reason.UNAVAILABLE -> "未找到可用的 su；请确认已安装并启用 Magisk/Alpha，或改用无线调试"
            RootShellStartException.Reason.DENIED -> "Root 授权未通过；请在 Magisk/Alpha 中允许 Onvyra，再重新点击启动"
            RootShellStartException.Reason.WRONG_UID -> "su 未切换到 shell UID2000，助手已拒绝启动"
            RootShellStartException.Reason.AUTHORIZATION_TIMEOUT -> "等待 Root 授权超时；请确认 Magisk/Alpha 弹窗后重新点击启动"
            RootShellStartException.Reason.HANDOFF_TIMEOUT -> "Root 已授权，但手机助手交接超时；请重新点击启动并保留错误类型"
        }
        chain.any { it is SocketTimeoutException || it is TimeoutException || it is TimeoutCancellationException } ||
            pairing?.reason == AdbPairingException.Reason.TIMEOUT -> "配对或连接超时；请保持系统配对弹窗打开，并核对当前端口"
        pairing?.reason == AdbPairingException.Reason.WRONG_CODE -> "配对认证未通过；请核对仍打开的系统弹窗中的最新配对码"
        pairing?.reason == AdbPairingException.Reason.EXPORT_FAILED -> "配对 TLS 密钥导出失败；请保留错误类型以便排查系统兼容性"
        pairing?.reason == AdbPairingException.Reason.CLOSED -> "配对连接已断开；请保持配对弹窗打开，关闭后端口会失效"
        pairing?.reason == AdbPairingException.Reason.TLS_FAILED ||
            connection?.reason == AdbConnectionException.Reason.TLS_FAILED || chain.any { it is SSLException } ->
            "TLS 握手未完成；配对请用弹窗中的配对端口，连接请用无线调试主页端口，两者不能混用"
        pairing?.reason == AdbPairingException.Reason.CONNECT_FAILED -> "无法连接配对服务；请保持配对弹窗打开，并核对手机地址与配对端口"
        connection?.reason == AdbConnectionException.Reason.UNAUTHORIZED -> "设备未接受连接身份或连接已中断；请确认已配对并核对连接端口"
        connection?.reason == AdbConnectionException.Reason.CONNECT_FAILED -> "无法连接手机助手；请核对无线调试主页的连接端口"
        pairing?.reason == AdbPairingException.Reason.PROTOCOL -> "配对协议响应不符；请确认使用配对端口"
        failure is IllegalArgumentException -> "配对参数无效；请核对本机地址、端口和六位配对码"
        else -> "手机助手连接准备或通信失败，请依据错误类型排查"
    }
    val stage = pairing?.stage?.let { "阶段=${it.name}；" }.orEmpty()
    val types = chain.map { it.javaClass.simpleName }.filter { it.matches(Regex("[A-Za-z][A-Za-z0-9_$]{0,80}")) }
        .distinct().take(4).joinToString(" / ")
    return "$description（$stage$types）"
}
