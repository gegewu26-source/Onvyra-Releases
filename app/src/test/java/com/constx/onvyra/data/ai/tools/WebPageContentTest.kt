package com.constx.onvyra.data.ai.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebPageContentTest {
    @Test
    fun extractsMetadataMainContentAndParagraphBoundariesWithoutScripts() {
        val page = extractWebPageContent(
            """
            <html lang="zh-CN"><head>
              <title>画布 &amp; 笔记</title><meta name="description" content="协作 &amp; 创作">
              <script>stealSecrets()</script><style>.hidden { display:none }</style>
            </head><body>
              <nav>重复菜单 <a href="/pricing">定价</a></nav>
              <main><h1>画布工具</h1><p>第一段 <strong>加粗</strong>。</p><p>第二段。</p>
                <ul><li>团队共享</li><li>离线编辑</li></ul>
                <div hidden>隐藏内容</div><div aria-hidden="true">装饰内容</div>
                <div style="DISPLAY: none !important;">样式隐藏内容</div>
                <div style="visibility: hidden">不可见内容</div>
                <script>maliciousInstruction()</script>
              </main><footer>重复页脚</footer>
            </body></html>
            """.trimIndent(),
            "https://example.com/",
        )

        assertEquals("画布 & 笔记", page.title)
        assertEquals("协作 & 创作", page.description)
        assertEquals("zh-CN", page.language)
        assertEquals("画布工具\n\n第一段 加粗。\n\n第二段。\n\n- 团队共享\n- 离线编辑", page.text)
        assertEquals(listOf(WebPageLink("定价", "https://example.com/pricing")), page.links)
        assertFalse(page.truncated)
    }

    @Test
    fun keepsHomepagePurposeAndAllCardsWhenThereIsNoMainElement() {
        val page = extractWebPageContent(
            """
            <html><head><meta property="og:title" content="NoteGrid">
              <meta property="og:description" content="A collaborative workspace"></head>
            <body><nav><a href="/docs">Documentation</a></nav>
              <header><h1>Plan, write and collaborate.</h1><p>A workspace for small teams.</p></header>
              <article><h2>Notes</h2><p>Keep your team's knowledge.</p></article>
              <article><h2>Boards</h2><p>Organize projects visually.</p></article>
            </body></html>
            """.trimIndent(),
            "https://example.com/",
        )

        assertEquals("NoteGrid", page.title)
        assertEquals("A collaborative workspace", page.description)
        assertTrue(page.text.contains("Plan, write and collaborate."))
        assertTrue(page.text.contains("A workspace for small teams."))
        assertTrue(page.text.contains("Keep your team's knowledge."))
        assertTrue(page.text.contains("Organize projects visually."))
    }

    @Test
    fun resolvesRelativeLinksAgainstHtmlBaseAndKeepsOnlyUniqueWebLinks() {
        val page = extractWebPageContent(
            """
            <head><base href="https://docs.example.com/guide/"></head><body><main>
              <a href="intro">Introduction</a><a href="intro">Duplicate</a>
              <a href="../pricing?from=docs&amp;plan=pro">Plans &amp; pricing</a>
              <a href="//other.example.com/help">Help</a>
              <a href="mailto:help@example.com">Email</a>
              <a href="javascript:alert(1)">Run script</a><a href="file:///etc/passwd">Local file</a>
              <a href="/image"><img alt="Image examples" src="example.png"></a>
            </main></body>
            """.trimIndent(),
            "https://example.com/start",
        )

        assertEquals(
            listOf(
                WebPageLink("Introduction", "https://docs.example.com/guide/intro"),
                WebPageLink("Plans & pricing", "https://docs.example.com/pricing?from=docs&plan=pro"),
                WebPageLink("Help", "https://other.example.com/help"),
                WebPageLink("Image examples", "https://docs.example.com/image"),
            ),
            page.links,
        )
    }

    @Test
    fun boundsLongTextAndLinksAndReportsTruncation() {
        val page = extractWebPageContent(
            "<main><p>" + "abcdef".repeat(10_000) + "</p>" +
                (1..100).joinToString("") { "<a href='/page/$it'>Page $it</a>" } + "</main>",
            "https://example.com/",
            maxChars = 127,
        )

        assertEquals("abcdef".repeat(22).take(127), page.text)
        assertTrue(page.truncated)
        assertEquals(30, page.links.size)
    }

    @Test
    fun exactTextLimitIsNotReportedAsTruncatedAndSurrogatePairsStayWhole() {
        val complete = extractWebPageContent("<p>Hello</p>", "https://example.com/", maxChars = 5)
        assertEquals("Hello", complete.text)
        assertFalse(complete.truncated)

        val cut = extractWebPageContent("<p>A😀B</p>", "https://example.com/", maxChars = 2)
        assertEquals("A", cut.text)
        assertTrue(cut.truncated)
    }

    @Test
    fun preservesAccessMessagesAndJavascriptShellMetadataWithoutInventingContent() {
        val login = extractWebPageContent(
            "<title>Members only</title><main><h1>Sign in to continue</h1><form>" +
                "<input name='password' value='secret'><button>Continue</button></form></main>",
            "https://example.com/account",
        )
        assertEquals("Sign in to continue", login.text)
        assertFalse(login.text.contains("secret"))

        val shell = extractWebPageContent(
            "<title>CloudCanvas</title><meta name='description' content='Design together online'>" +
                "<div id='root'></div><script>renderEverything()</script>",
            "https://example.com/",
        )
        assertEquals("CloudCanvas", shell.title)
        assertEquals("Design together online", shell.description)
        assertEquals("", shell.text)
    }

    @Test
    fun emptyMainFallsBackToUsefulBodyAndNavigationOnlyPageKeepsItsText() {
        val fallback = extractWebPageContent(
            "<body><main></main><h1>Fast file transfers</h1><p>Send files privately.</p></body>",
            "https://example.com/",
        )
        assertTrue(fallback.text.contains("Send files privately."))

        val navigation = extractWebPageContent(
            "<nav><a href='/products'>Explore our video editing products</a></nav>",
            "https://example.com/",
        )
        assertEquals("Explore our video editing products", navigation.text)
        assertEquals(1, navigation.links.size)
    }

    @Test
    fun oneSmallNewsCardDoesNotReplaceTheHomepageIntroduction() {
        val page = extractWebPageContent(
            "<header><h1>Acme Audio</h1><p>Record, edit and mix your music in one collaborative " +
                "workspace. Professional sound tools for independent musicians.</p></header>" +
                "<article><h2>Latest news</h2><p>Version two released.</p></article>",
            "https://example.com/",
        )
        assertTrue(page.text.contains("Record, edit and mix your music"))
        assertTrue(page.text.contains("Version two released."))
    }
}
