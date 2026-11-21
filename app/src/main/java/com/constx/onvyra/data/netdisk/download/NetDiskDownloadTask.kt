package com.constx.onvyra.data.netdisk.download

import kotlinx.serialization.Serializable

@Serializable
enum class NetDiskDownloadStatus { QUEUED, PROBING, DOWNLOADING, PAUSED, SAVING, COMPLETED, FAILED, CANCELLED }

/** Public progress contains no share URLs, request headers or account material. */
@Serializable
data class NetDiskDownloadTask(
    val id: String,
    val fileName: String,
    val mimeType: String = "application/octet-stream",
    val status: NetDiskDownloadStatus = NetDiskDownloadStatus.QUEUED,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = -1L,
    val speedBytesPerSecond: Long = 0L,
    val activeConnections: Int = 0,
    val connectionLimit: Int = 8,
    val errorMessage: String? = null,
    val outputUri: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val mode: String = "",
    val destinationTreeUri: String? = null,
    val destinationLabel: String? = null,
    val publicationPending: Boolean = false,
)
