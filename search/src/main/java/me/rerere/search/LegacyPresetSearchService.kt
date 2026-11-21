package me.rerere.search

import androidx.compose.runtime.Composable
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.core.InputSchema

/** Decode-only compatibility for obsolete settings. Never contact the removed endpoint. */
object LegacyPresetSearchService : SearchService<SearchServiceOptions.LegacyPresetOptions> {
    override val name: String = "已停用服务"
    @Composable override fun Description() {}
    override fun parameters(options: SearchServiceOptions.LegacyPresetOptions): InputSchema? = null
    override fun scrapingParameters(options: SearchServiceOptions.LegacyPresetOptions): InputSchema? = null
    override suspend fun search(params: JsonObject, commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.LegacyPresetOptions): Result<SearchResult> =
        Result.failure(IllegalStateException("旧版内置搜索服务已停用，请选择搜索提供商"))
    override suspend fun scrape(params: JsonObject, commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.LegacyPresetOptions): Result<ScrapedResult> =
        Result.failure(IllegalStateException("旧版内置搜索服务已停用，请选择搜索提供商"))
}
