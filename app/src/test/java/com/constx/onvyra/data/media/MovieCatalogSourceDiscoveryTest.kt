package com.constx.onvyra.data.media

import org.junit.Assert.*
import org.junit.Test

class MovieCatalogSourceDiscoveryTest {
    @Test fun supportedHttpCatalogsAreIndependentOfSpiderProvidersAndUseStableApiIdentity() {
        val sources = movieCatalogSourcesFromSites(listOf(
            MovieDiscoverySite("CMS", "https://cms.example.test/api.php/provide/vod/?ac=list", type = 1),
            MovieDiscoverySite("T4", "https://t4.example.test/api", type = 4),
            MovieDiscoverySite("明确API", "https://other.example.test/api.php/provide/vod/", type = 0),
            MovieDiscoverySite("独立HTTP扩展", "csp_Provider", "https://ext.example.test/api.php/provide/vod/", 3),
            MovieDiscoverySite("Spider", "csp_Spider", "https://example.test/rules.js", 3),
            MovieDiscoverySite("Jar", "https://example.test/spider.jar", type = 4),
        ), "https://config.example.test/root.json")
        assertEquals(4, sources.size)
        assertTrue(sources.all { it.name.startsWith("发现·") && it.id == discoveredMovieSourceId(it.apiUrl) })
        assertEquals("https://cms.example.test/api.php/provide/vod/", sources.first().apiUrl)
        assertFalse(sources.any { "csp_" in it.apiUrl || it.apiUrl.endsWith(".js") || it.apiUrl.endsWith(".jar") })
    }

    @Test fun credentialLocalAndFakeRelativePluginEntriesAreNotCatalogs() {
        val sources = movieCatalogSourcesFromSites(listOf(
            MovieDiscoverySite("relative", "/api.php/provide/vod/", type = 1),
            MovieDiscoverySite("fake", "drpy_provider", type = 1),
            MovieDiscoverySite("local", "http://127.0.0.1/api.php/provide/vod/", type = 1),
            MovieDiscoverySite("credentials", "https://user:password@example.test/api", type = 4),
            MovieDiscoverySite("nonweb", "file:///private/api.php/provide/vod/", type = 1),
            MovieDiscoverySite("invalid", "javascript:alert(1)", type = 4),
        ), "https://config.example.test/folder/config.json")
        assertEquals(listOf("https://config.example.test/api.php/provide/vod/"), sources.map { it.apiUrl })
    }

    @Test fun repeatedApiUrlsAndLargeConfigsProduceABoundedProviderList() {
        val sources = movieCatalogSourcesFromSites((0..700).map {
            MovieDiscoverySite("站$it", "https://api.example.test/$it/api.php/provide/vod/", type = 1)
        } + MovieDiscoverySite("重复", "https://api.example.test/0/api.php/provide/vod/?ac=list", type = 1),
            "https://config.example.test/config.json")
        assertEquals(128, sources.size)
        assertEquals(128, sources.map { it.id }.distinct().size)
    }

    @Test fun sourcePagesKeepActualRawRepositoryConfigsButIgnoreCodeAndMediaFiles() {
        val links = extractMovieDiscoveryConfigLinks("""
            <a href="https://raw.githubusercontent.com/owner/repo/main/config.json">GitHub配置</a>
            <a href="https://gitlab.com/owner/repo/-/raw/main/tv.json">GitLab配置</a>
            <a href="../box/config.txt">相对仓库</a>
            <a href="https://example.test/plugin.js">脚本</a>
            <a href="https://example.test/player.jar">插件</a>
            <a href="http://localhost/api.json">本地</a>
            raw https:\/\/cdn.example.test\/tvbox.json
            image https://example.test/image.png
        """.trimIndent(), "https://page.example.test/folder/list.html")
        assertTrue(links.contains("https://raw.githubusercontent.com/owner/repo/main/config.json"))
        assertTrue(links.contains("https://gitlab.com/owner/repo/-/raw/main/tv.json"))
        assertTrue(links.contains("https://page.example.test/box/config.txt"))
        assertTrue(links.contains("https://cdn.example.test/tvbox.json"))
        assertEquals(4, links.size)
    }
}
