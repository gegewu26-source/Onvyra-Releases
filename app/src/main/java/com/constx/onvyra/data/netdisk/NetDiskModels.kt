package com.constx.onvyra.data.netdisk

import kotlinx.serialization.json.JsonObject

enum class NetDiskPlatform(val label: String, val loginUrl: String, val credentialLabel: String = "Cookie") {
    LANZOU("蓝奏云", "https://pc.woozooo.com/"),
    ILANZOU("蓝奏云优享版", "https://www.ilanzou.com/", "appToken / uuid"),
    PAN123("123 云盘", "https://www.123pan.com/", "Authorization / Token"),
    ALIYUN("阿里云盘", "https://www.alipan.com/", "Authorization / Token"),
    QUARK("夸克网盘", "https://pan.quark.cn/"),
    UC("UC 网盘", "https://drive.uc.cn/"),
    BAIDU("百度网盘", "https://pan.baidu.com/"),
    XUNLEI("迅雷云盘", "https://pan.xunlei.com/", "Authorization / Token"),
    PAN115("115 网盘", "https://115.com/"),
    C139("移动云盘", "https://yun.139.com/"),
    GUANGYA("光鸭云盘", "https://www.guangyapan.com/", "Authorization / Token");
}

data class NetDiskShare(val platform: NetDiskPlatform, val url: String, val shareId: String, val password: String = "")

data class NetDiskSession(val share: NetDiskShare, val title: String = "分享文件", val token: String = "",
    val extra: JsonObject = JsonObject(emptyMap()))

data class NetDiskFile(val id: String, val name: String, val isDirectory: Boolean = false, val size: Long = 0L,
    val mimeType: String = "application/octet-stream", val extra: JsonObject = JsonObject(emptyMap()))

data class NetDiskListing(val files: List<NetDiskFile>, val nextCursor: String? = null, val title: String? = null)

data class NetDiskDownload(val url: String, val fileName: String, val mimeType: String = "application/octet-stream",
    val headers: Map<String, String> = emptyMap(), val note: String? = null)

class NetDiskException(val reason: String, override val message: String) : Exception(message)

/** Each implementation talks directly to its provider. No parser proxy URL exists. */
interface NetDiskProvider {
    val platform: NetDiskPlatform
    suspend fun open(share: NetDiskShare, credential: String): NetDiskSession
    suspend fun list(session: NetDiskSession, directoryId: String = "", cursor: String? = null,
        credential: String = ""): NetDiskListing
    suspend fun resolve(session: NetDiskSession, file: NetDiskFile, credential: String): NetDiskDownload
}
