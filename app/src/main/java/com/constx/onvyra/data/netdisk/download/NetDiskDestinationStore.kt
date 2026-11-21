package com.constx.onvyra.data.netdisk.download

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.constx.onvyra.data.netdisk.NetDiskException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class NetDiskDestination(val treeUri: String? = null, val label: String = "Download/Onvyra/网盘")

/** The user's explicitly selected folder, with a persisted Storage Access Framework grant. */
internal class NetDiskDestinationStore private constructor(context: Context) {
    private val app = context.applicationContext
    private val resolver = app.contentResolver
    private val preferences = app.getSharedPreferences("netdisk-download-directory", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(NetDiskDestination(preferences.getString("tree", null),
        preferences.getString("label", null) ?: "Download/Onvyra/网盘"))
    val current = mutable.asStateFlow()

    @Synchronized fun select(uri: Uri) {
        if (uri.scheme != "content" || !DocumentsContract.isTreeUri(uri))
            throw NetDiskException("directory_invalid", "请选择一个可写入的文件夹。")
        val wasPersisted = resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission && it.isWritePermission }
        try {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            ensureAccess(uri.toString())
            val document = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
            val name = resolver.query(document, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }.orEmpty()
            val id = DocumentsContract.getTreeDocumentId(uri)
            val label = if (uri.authority == "com.android.externalstorage.documents") id.substringAfter(':').ifBlank { name }
                else name
            val value = NetDiskDestination(uri.toString(), label.ifBlank { "已选文件夹" })
            if (!preferences.edit().putString("tree", value.treeUri).putString("label", value.label).commit())
                throw NetDiskException("directory_store", "无法保存目录设置，请检查手机存储空间。")
            mutable.value = value
        } catch (error: Exception) {
            if (!wasPersisted) runCatching { resolver.releasePersistableUriPermission(uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            if (error is NetDiskException) throw error
            throw NetDiskException("directory_access", "无法保留此目录的访问权限，请重新选择可写文件夹。")
        }
    }

    @Synchronized fun reset() {
        if (!preferences.edit().remove("tree").remove("label").commit())
            throw NetDiskException("directory_store", "目录设置保存失败，请重试。")
        // Older tasks still own their original directory choice and its permission.
        mutable.value = NetDiskDestination()
    }

    fun ensureAccess(value: String?) {
        if (value == null) return
        val uri = Uri.parse(value)
        if (uri.scheme != "content" || !DocumentsContract.isTreeUri(uri) ||
            resolver.persistedUriPermissions.none { it.uri == uri && it.isReadPermission && it.isWritePermission })
            throw NetDiskException("directory_access", "自定义下载目录的访问权限已失效，请在解析页重新选择目录。")
        val document = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
        val writable = resolver.query(document, arrayOf(DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_FLAGS), null, null, null)?.use {
            it.moveToFirst() && it.getString(0) == DocumentsContract.Document.MIME_TYPE_DIR &&
                (it.getLong(1) and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE.toLong()) != 0L
        } == true
        if (!writable) throw NetDiskException("directory_access", "所选下载目录无法写入，请在解析页更换目录。")
    }

    companion object {
        @Volatile private var shared: NetDiskDestinationStore? = null
        fun get(context: Context): NetDiskDestinationStore = shared ?: synchronized(this) {
            shared ?: NetDiskDestinationStore(context).also { shared = it }
        }
    }
}
