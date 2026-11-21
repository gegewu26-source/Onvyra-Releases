package com.constx.onvyra.data.media

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class VideoLinkParserTest {
    @Test fun `Bilibili BV av player pages and page timestamp parameters use official embed`() {
        val bv = parseVideoLink("https://www.bilibili.com/video/BV1xx411c7mD/?p=2&t=1m30s&share_source=copy")!!
        assertEquals(VideoPlatform.BILIBILI, bv.platform)
        assertEquals("BV1xx411c7mD", bv.videoId)
        val embed = bv.embedUrl!!.toHttpUrl()
        assertEquals("player.bilibili.com", embed.host)
        assertEquals("BV1xx411c7mD", embed.queryParameter("bvid"))
        assertEquals("2", embed.queryParameter("p"))
        assertEquals("90", embed.queryParameter("t"))
        assertEquals("0", embed.queryParameter("autoplay"))
        assertEquals("170001", parseVideoLink("https://m.bilibili.com/video/av170001")!!.embedUrl!!.toHttpUrl().queryParameter("aid"))
        assertEquals("av170001", parseVideoLink("https://player.bilibili.com/player.html?aid=170001")!!.videoId)
    }

    @Test fun `Douyin canonical share modal and official player URLs map to public player`() {
        val urls = listOf("https://www.douyin.com/video/7123456789012345678",
            "https://www.iesdouyin.com/share/video/7123456789012345678/?x=1",
            "https://www.douyin.com/?modal_id=7123456789012345678",
            "https://open.douyin.com/player/video?vid=7123456789012345678")
        urls.forEach { url ->
            val link = parseVideoLink(url)!!
            assertEquals(VideoPlatform.DOUYIN, link.platform)
            assertEquals("7123456789012345678", link.videoId)
            assertEquals("https://open.douyin.com/player/video?vid=7123456789012345678&autoplay=0", link.embedUrl)
        }
        assertNull(parseVideoLink("https://www.douyin.com/note/7123456789012345678"))
    }

    @Test fun `YouTube watch shorts live embeds short host and timestamps are recognized`() {
        listOf("https://www.youtube.com/watch?v=dQw4w9WgXcQ", "https://youtu.be/dQw4w9WgXcQ",
            "https://m.youtube.com/shorts/dQw4w9WgXcQ", "https://youtube.com/live/dQw4w9WgXcQ",
            "https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ").forEach { url ->
            assertEquals("dQw4w9WgXcQ", parseVideoLink(url)!!.videoId)
        }
        val embed = parseVideoLink("https://youtu.be/dQw4w9WgXcQ?t=1h2m3s")!!.embedUrl!!.toHttpUrl()
        assertEquals("www.youtube-nocookie.com", embed.host)
        assertEquals("3723", embed.queryParameter("start"))
        assertEquals("1", embed.queryParameter("enablejsapi"))
        assertEquals("83", parseVideoLink("https://www.youtube.com/watch?v=dQw4w9WgXcQ#t=83")!!.embedUrl!!.toHttpUrl().queryParameter("start"))
    }

    @Test fun `TikTok and Vimeo IDs and player pages are mapped without API requests`() {
        val tiktok = parseVideoLink("https://www.tiktok.com/@creator/video/7432143583318744351")!!
        assertEquals(VideoPlatform.TIKTOK, tiktok.platform)
        assertEquals("https://www.tiktok.com/player/v1/7432143583318744351?autoplay=0", tiktok.embedUrl)
        assertEquals("7432143583318744351", parseVideoLink(tiktok.embedUrl!!)!!.videoId)
        val vimeo = parseVideoLink("https://vimeo.com/76979871/a1b2c3d4")!!
        assertEquals(VideoPlatform.VIMEO, vimeo.platform)
        assertEquals("a1b2c3d4", vimeo.embedUrl!!.toHttpUrl().queryParameter("h"))
        assertEquals("76979871", parseVideoLink("https://vimeo.com/channels/staffpicks/76979871")!!.videoId)
        assertEquals("76979871", parseVideoLink("https://player.vimeo.com/video/76979871?h=secret")!!.videoId)
    }

    @Test fun `short share hosts are candidates for asynchronous resolution`() {
        mapOf("https://b23.tv/abc123" to VideoPlatform.BILIBILI,
            "https://v.douyin.com/AbCdEf/" to VideoPlatform.DOUYIN,
            "https://vm.tiktok.com/ZMh123/" to VideoPlatform.TIKTOK,
            "https://vt.tiktok.com/ZMh456/" to VideoPlatform.TIKTOK,
            "https://www.tiktok.com/t/ZMh456/" to VideoPlatform.TIKTOK).forEach { (url, platform) ->
            val link = parseVideoLink(url)!!
            assertEquals(platform, link.platform)
            assertNull(link.embedUrl)
        }
    }

    @Test fun `direct media paths ignore case retain signed queries and advertise proper mime`() {
        listOf("mp4", "MOV", "WebM", "m3u8", "mpd", "m4v").forEach { extension ->
            val url = "https://cdn.example.test/clip.$extension?token=signed%2Fvalue&expires=999#t=10"
            val link = parseVideoLink(url)!!
            assertEquals(VideoPlatform.DIRECT, link.platform)
            assertEquals(url, link.url)
            assertNotNull(videoMimeType(link.url))
        }
    }

    @Test fun `other video routes stay webpage fallbacks but ordinary posts are ignored`() {
        listOf("https://www.kuaishou.com/short-video/abc123", "https://v.kuaishou.com/abc123",
            "https://weibo.com/tv/show/1034:12345", "https://video.weibo.com/show?fid=1034:12345",
            "https://www.instagram.com/reel/abc123/", "https://x.com/creator/status/12345/video/1").forEach {
            assertEquals(VideoPlatform.OTHER, parseVideoLink(it)!!.platform)
        }
        assertNull(parseVideoLink("https://x.com/creator/status/12345"))
        assertNull(parseVideoLink("https://www.instagram.com/p/photo123/"))
    }

    @Test fun `host boundaries unsafe schemes credentials roots and profiles are not video links`() {
        listOf("https://www.bilibili.com.evil.test/video/BV1xx411c7mD",
            "https://notdouyin.com/video/7123456789012345678", "https://youtube.com.evil.test/watch?v=dQw4w9WgXcQ",
            "https://evil.test/?url=https%3A%2F%2Fyoutu.be%2FdQw4w9WgXcQ",
            "file:///storage/clip.mp4", "javascript:https://youtu.be/dQw4w9WgXcQ",
            "https://name:password@www.bilibili.com/video/BV1xx411c7mD",
            "https://bilibili.com/", "https://www.youtube.com/@creator", "https://douyin.com/user/profile",
            "https://vimeo.com/user123", "https://example.test/help").forEach { assertNull(it, parseVideoLink(it)) }
    }

    @Test fun `share prose Markdown punctuation tracking variants and duplicates are handled`() {
        val text = "看看这个：[演示](https://www.bilibili.com/video/BV1xx411c7mD/?share_source=a)，\n" +
            "重复 https://www.bilibili.com/video/BV1xx411c7mD/?share_source=b。\n" +
            "另一个视频 https://youtu.be/dQw4w9WgXcQ！普通 https://example.test/readme"
        val links = extractVideoLinks(text)
        assertEquals(2, links.size)
        assertEquals(listOf(VideoPlatform.BILIBILI, VideoPlatform.YOUTUBE), links.map { it.platform })
        assertFalse(links.first().url.endsWith(")"))
    }

    @Test fun `fenced inline and unfinished code examples never create previews`() {
        val hidden = "https://youtu.be/dQw4w9WgXcQ"
        val visible = "https://vimeo.com/76979871"
        assertEquals(listOf(VideoPlatform.VIMEO), extractVideoLinks("```text\n$hidden\n```\n$visible").map { it.platform })
        assertEquals(listOf(VideoPlatform.VIMEO), extractVideoLinks("~~~\n$hidden\n~~~~\n$visible").map { it.platform })
        assertEquals(listOf(VideoPlatform.VIMEO), extractVideoLinks("`$hidden` and ``$hidden``\n$visible").map { it.platform })
        assertEquals(listOf(VideoPlatform.VIMEO), extractVideoLinks("```$hidden```\n$visible").map { it.platform })
        assertTrue(extractVideoLinks("before\n```\n$hidden").isEmpty())
    }

    @Test fun `URLs with balanced query parentheses keep their full value`() {
        val links = extractVideoLinks("[clip](https://cdn.example.test/clip.mp4?name=(intro))")
        assertEquals("https://cdn.example.test/clip.mp4?name=(intro)", links.single().url)
    }

    @Test fun `invalid identifiers and timestamp overflow are rejected or omitted`() {
        assertNull(parseVideoLink("https://bilibili.com/video/BVbad"))
        assertNull(parseVideoLink("https://youtube.com/watch?v=bad"))
        assertNull(parseVideoLink("https://douyin.com/video/not-numeric"))
        val embed = parseVideoLink("https://youtu.be/dQw4w9WgXcQ?t=99999999999999999999h")!!.embedUrl!!.toHttpUrl()
        assertNull(embed.queryParameter("start"))
    }
    @Test fun `adjacent Chinese punctuation separates a sharing URL from following prose`() {
        val result = extractVideoLinks("看看https://youtu.be/dQw4w9WgXcQ。这个视频很好！")
        assertEquals("dQw4w9WgXcQ", result.single().videoId)
    }

    @Test fun `timestamp unit overflow is omitted while valid zero and integer limit remain valid`() {
        listOf("99999999999999999999h", "99999999999999999999m", "99999999999999999999s",
            "2147483648s", "596524h").forEach { timestamp ->
            val embed = parseVideoLink("https://youtu.be/dQw4w9WgXcQ?t=$timestamp")!!.embedUrl!!.toHttpUrl()
            assertNull(timestamp, embed.queryParameter("start"))
        }
        assertEquals("0", parseVideoLink("https://youtu.be/dQw4w9WgXcQ?t=0s")!!.embedUrl!!.toHttpUrl().queryParameter("start"))
        assertEquals("2147483647", parseVideoLink("https://youtu.be/dQw4w9WgXcQ?t=2147483647s")!!.embedUrl!!.toHttpUrl().queryParameter("start"))
    }

    @Test fun `Bilibili episode and season links retain the real playback page instead of a fake BV iframe`() {
        listOf("ep199612", "ss12548").forEach { id ->
            val url = "https://www.bilibili.com/bangumi/play/$id?theme=movie"
            val link = parseVideoLink(url)!!
            assertEquals(VideoPlatform.OTHER, link.platform)
            assertEquals(id, link.videoId)
            assertEquals(url, link.url)
            assertNull(link.embedUrl)
            assertEquals(listOf(link), extractVideoLinks("看这部电影：$url"))
        }
        assertNull(parseVideoLink("https://www.bilibili.com/bangumi/media/md12345"))
        assertNull(parseVideoLink("https://www.bilibili.com/bangumi/play/ep-not-a-number"))
    }

}
