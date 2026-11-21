package com.constx.onvyra.ui.pages.novel

import kotlinx.serialization.Serializable

@Serializable data class ReaderStoreCategory(val id: String, val title: String, val channel: String = "male")
@Serializable data class ReaderStoreSection(val category: ReaderStoreCategory, val books: List<NovelBook>, val horizontal: Boolean = false)
@Serializable data class ReaderStoreHome(val channel: String = "male", val featured: List<NovelBook> = emptyList(),
    val sections: List<ReaderStoreSection> = emptyList(), val stale: Boolean = false, val notice: String? = null,
    val categories: List<ReaderStoreCategory> = emptyList())
data class ReaderStoreCategoryPage(val category: ReaderStoreCategory, val books: List<NovelBook> = emptyList(),
    val page: Int = 1, val hasMore: Boolean = true, val loading: Boolean = false, val error: String? = null)
