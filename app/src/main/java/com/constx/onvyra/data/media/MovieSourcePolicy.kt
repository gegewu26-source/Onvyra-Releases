package com.constx.onvyra.data.media

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Keep the reported advertising feed out of searches, including copies rediscovered from TVBox. */
internal fun isExcludedMovieCatalogSource(source: MovieCatalogSource): Boolean {
    val host = source.apiUrl.toHttpUrlOrNull()?.host.orEmpty()
    return source.id == "baofeng" || host == "bfzyapi.com" || host.endsWith(".bfzyapi.com")
}
