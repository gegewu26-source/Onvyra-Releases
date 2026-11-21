package com.constx.onvyra.data.repository

import androidx.paging.PagingSource
import com.constx.onvyra.data.db.dao.GenMediaDAO
import com.constx.onvyra.data.db.entity.GenMediaEntity

class GenMediaRepository(private val dao: GenMediaDAO) {
    fun getAllMedia(): PagingSource<Int, GenMediaEntity> = dao.getAll()

    suspend fun insertMedia(media: GenMediaEntity) = dao.insert(media)

    suspend fun deleteMedia(id: Int) = dao.delete(id)
}
