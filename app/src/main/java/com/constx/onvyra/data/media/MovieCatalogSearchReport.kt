package com.constx.onvyra.data.media

import java.io.IOException

internal enum class MovieCatalogPayloadFailure { API_ERROR, INVALID_FORMAT }

/** The remote response/exception text is deliberately not included in errors sent to a model. */
internal class MovieCatalogPayloadException(val reason: MovieCatalogPayloadFailure) : IOException(
    if (reason == MovieCatalogPayloadFailure.API_ERROR) "影片目录返回接口错误" else "影片目录返回的数据格式无效",
)

internal enum class MovieCatalogFailureCode(val wireValue: String) {
    NETWORK("network_error"), TIMEOUT("timeout"), HTTP("http_error"),
    INVALID_RESPONSE("invalid_response"), API_ERROR("api_error"), UNKNOWN("source_error"),
}

internal data class MovieCatalogSourceFailure(val sourceId: String, val code: MovieCatalogFailureCode)

internal data class MovieCatalogSearchReport(
    val totalSources: Int,
    val attemptedSources: Int,
    val successfulSources: Int,
    val emptySources: Int,
    val failures: List<MovieCatalogSourceFailure>,
    val timedOutSources: Int,
    val unqueriedSources: Int,
    /** Whole compatible pool before this invocation's optional source-ID filter. */
    val eligibleSources: Int = totalSources,
    val selectedSourceIds: Set<String> = emptySet(),
    val attemptedSourceIds: Set<String> = emptySet(),
    val successfulSourceIds: Set<String> = emptySet(),
    val emptySourceIds: Set<String> = emptySet(),
    val eligibleSourceIds: Set<String> = emptySet(),
) {
    val complete: Boolean get() = totalSources > 0 && successfulSources == totalSources && attemptedSources == totalSources && failures.isEmpty() && unqueriedSources == 0
}

internal data class MovieCatalogSearchResult(val items: List<MovieCatalogItem>, val report: MovieCatalogSearchReport)

/** Configuration entry points and compatible catalogs are different units, never playback counts. */
internal data class MovieCatalogSourceInventory(
    val bundledConfigRoots: Int,
    val bundledDiscoveryPages: Int,
    val builtInCatalogs: Int,
    val registeredCatalogs: Int,
    val eligibleCatalogs: Int,
    val eligibleSourceIds: Set<String> = emptySet(),
)

internal fun combineCatalogSearchReports(reports: List<MovieCatalogSearchReport>): MovieCatalogSearchReport? {
    if (reports.isEmpty()) return null
    val haveIds = reports.all { it.selectedSourceIds.size == it.totalSources }
    val selected = reports.flatMapTo(linkedSetOf()) { it.selectedSourceIds }
    val attempted = reports.flatMapTo(linkedSetOf()) { it.attemptedSourceIds }
    val successful = reports.flatMapTo(linkedSetOf()) { it.successfulSourceIds }
    val empty = reports.flatMapTo(linkedSetOf()) { it.emptySourceIds }
    val eligible = reports.flatMapTo(linkedSetOf()) { it.eligibleSourceIds }
    val failures = reports.flatMap { it.failures }.distinctBy { it.sourceId }.filterNot { it.sourceId in successful }
    return MovieCatalogSearchReport(
        if (haveIds) selected.size else reports.sumOf { it.totalSources },
        if (haveIds) attempted.size else reports.sumOf { it.attemptedSources },
        if (haveIds) successful.size else reports.sumOf { it.successfulSources },
        if (haveIds) empty.size else reports.sumOf { it.emptySources }, failures,
        failures.count { it.code == MovieCatalogFailureCode.TIMEOUT },
        if (haveIds) (selected - attempted).size else reports.sumOf { it.unqueriedSources },
        maxOf(reports.maxOf { it.eligibleSources }, eligible.size), selected, attempted, successful, empty, eligible,
    )
}

internal open class MovieCatalogLookupException(
    val catalogStatus: String,
    val report: MovieCatalogSearchReport?,
    message: String,
) : IOException(message)

internal class MovieCatalogSearchException(report: MovieCatalogSearchReport) : MovieCatalogLookupException(
    if (report.successfulSources > 0) "partial_search" else "sources_unavailable", report,
    "影片目录未能完整查询，请根据来源状态重试或继续搜索其他真实来源。",
)

internal class MovieCatalogNoMatchException(report: MovieCatalogSearchReport) : MovieCatalogLookupException(
    if (report.complete) "no_match" else "partial_search", report,
    if (report.complete) "本次已查询目录未找到对应影片，不代表影片不存在。"
    else "部分目录失败或尚未查完，当前没有找到匹配影片，不能断言没有片源。",
)

internal class MovieCatalogPlaybackException(report: MovieCatalogSearchReport) : MovieCatalogLookupException(
    "playback_failed", report, "已找到匹配影片，但当前播放线路未通过验证。",
)

/** The primary factory queries disjoint built-in/saved groups; do not lose the first group's failure. */
internal fun combineCatalogLookupFailures(first: Exception, second: Exception): MovieCatalogLookupException {
    val a = first as? MovieCatalogLookupException
    val b = second as? MovieCatalogLookupException
    val reports = listOfNotNull(a?.report, b?.report)
    val report = combineCatalogSearchReports(reports)
    val status = when {
        a?.catalogStatus == "playback_failed" || b?.catalogStatus == "playback_failed" -> "playback_failed"
        a?.catalogStatus == "no_match" && b?.catalogStatus == "no_match" && report?.complete == true -> "no_match"
        a?.catalogStatus in setOf("partial_search", "no_match") || b?.catalogStatus in setOf("partial_search", "no_match") -> "partial_search"
        else -> "sources_unavailable"
    }
    return MovieCatalogLookupException(status, report, "影视目录分组查询未取得可验证结果，请参照来源状态。")
}
