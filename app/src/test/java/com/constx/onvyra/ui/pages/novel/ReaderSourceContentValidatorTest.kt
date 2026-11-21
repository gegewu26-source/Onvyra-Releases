package com.constx.onvyra.ui.pages.novel

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ReaderSourceContentValidatorTest {
    @Test fun emptyHeadingsAndRecognizedNoticesCannotBecomeReadableChapterBodies() {
        for (text in listOf(
            "", " \n\u200b\ufeff", "第一章 初见", "……", "系统提示：", "请求失败，请稍后重试。",
            "访问过于频繁，请稍后重试。", "请登录后阅读。", "请先登录或注册",
            "本章为VIP章节，请订阅后阅读。", "请下载APP继续阅读。",
            "请完成人机验证后继续。", "系统提示：该章节已下架。",
            "广告推广：https://ads.example.test/promo", "404 Not Found", "HTTP 503 Service Unavailable",
            "第一章 初见\n请登录后阅读。\n上一章\n返回目录\n下一章",
        )) {
            assertThrows("Notices must not count as prose: $text", IOException::class.java) {
                ReaderSourceContentValidator.requireReadable(text, "第一章 初见")
            }
        }
    }

    @Test fun shortChaptersAndStoriesDiscussingTechnicalOrAdvertisingWordsArePreservedExactly() {
        for (text in listOf(
            "完。", "春眠不觉晓。", "　　他指着墙上的广告，问她看懂了没有。\n\n　　她没有回答。",
            "他说：‘验证码已经收到，请登录。’然后放下手机。", "第404号房间里，故事才刚刚开始。",
            "访问过于频繁，请稍后重试。张三看着屏幕，转身离开。",
        )) {
            assertEquals(text, ReaderSourceContentValidator.requireReadable(text))
        }
    }

    @Test fun aNoticeAlongsideActualStoryDoesNotMakeTheWholeChapterUnavailable() {
        val text = "广告推广：https://ads.example.test/promo\n\n她走进院子，看见门边落着一只旧鞋。\n\n返回目录"
        assertEquals(text, ReaderSourceContentValidator.requireReadable(text))
    }

    @Test fun explicitChapterMismatchIsRejectedEvenWhenBodyTextLooksPlausible() {
        assertThrows(IOException::class.java) {
            ReaderSourceContentValidator.requireReadable("他推开门，故事开始了。", "第一章 初见", "第三章 远行")
        }
    }

    @Test fun renumberedSameChapterAndAnExplicitPageSuffixRetainTheOriginalBody() {
        val text = "　　门开了。"
        assertEquals(text, ReaderSourceContentValidator.requireReadable(text, "第一章 初见", "第99章 初见"))
        assertEquals(text, ReaderSourceContentValidator.requireReadable(text, "第一章 初见", "第一章 初见（第2页）"))
    }

    @Test fun missingOrGenericExpectedTitleDoesNotInventAChapterMismatch() {
        assertEquals("完。", ReaderSourceContentValidator.requireReadable("完。", "", "作品站点"))
        assertEquals("完。", ReaderSourceContentValidator.requireReadable("完。", "正文", "第一章 初见"))
    }

    @Test fun meaningfulChapterPunctuationMustNotBeErasedToMakeAnotherChapterMatch() {
        assertThrows(IOException::class.java) {
            ReaderSourceContentValidator.requireReadable("故事正文。", "第一章 甲·乙", "第99章 甲乙")
        }
    }
}
