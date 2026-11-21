package com.constx.onvyra.ui.pages.novel

import java.io.IOException

/** Reject clear non-content responses; length or a keyword alone cannot establish readability. */
internal object ReaderSourceContentValidator {
    private val spacing = Regex("[\\s\\u00a0\\u200b\\u3000\\ufeff]+")
    private val clauseBreak = Regex("[，,。；;！!]+")
    private val pageSuffix = Regex("[（(](?:第?\\d+页|\\d+/\\d+)[）)]$")
    private val noticePrefix = Regex("^(?:温馨提示|系统提示|错误提示|提示|通知)[：:]")
    private val englishNotice = Regex(
        "(?:http(?:/\\d(?:\\.\\d)?)?[:：]?)?(?:" +
            "(?:400)?badrequest|(?:401)?unauthorized|(?:403)?forbidden|(?:404)?notfound|" +
            "(?:429)?toomanyrequests|(?:500)?internalservererror|(?:502)?badgateway|" +
            "(?:503)?serviceunavailable|(?:504)?gatewaytimeout|accessdenied|" +
            "404|403|429|please(?:log|sign)into(?:continue|read)|" +
            "pleaseverify(?:youarehuman|yourbrowser)|justamoment|checkingyourbrowser" +
            ")[.!。！…]*",
        RegexOption.IGNORE_CASE,
    )
    // These expressions match complete notice clauses, never arbitrary substrings of prose.
    private val noticeClauses = listOf(
        "(?:抱歉|对不起|温馨提示|系统提示|提示|正文|章节正文|广告|广告推广)",
        "(?:请求失败|服务异常|系统错误|网络错误|服务器错误|本章未完)",
        "(?:网页|页面|章节|当前章节|该章节|本章|内容)?(?:不存在|未找到|已删除|已下架|已失效|无法访问|加载失败|加载中|暂时不可用|正在加载)",
        "(?:404)?(?:页面|网页)(?:不存在|未找到|走丢了)",
        "(?:您(?:的)?)?(?:访问过于频繁|请求过于频繁|操作过于频繁|访问频繁|请求频率过高|操作频繁|访问被拒绝)",
        "请(?:稍后|稍候)(?:重试|再试|访问|再访问)",
        "(?:请先|请|需要|必须|您需要|本章需要|当前章节需要)?(?:登录|登陆)(?:后|后方可|后才可)?(?:继续阅读|阅读(?:本章|本章节|全文|完整内容)?|查看(?:本章|正文)?|访问)?",
        "请(?:先)?(?:登录或注册|注册或登录|登录账号|登录账户)",
        "(?:本章|当前章节|该章节)(?:为|是)?(?:VIP|vip|付费|会员|订阅)章节",
        "请(?:先)?(?:订阅|购买|开通会员)(?:后|后再)?(?:继续阅读|阅读|查看正文)",
        "(?:请先|请|需要)?(?:完成|通过)(?:安全|人机|浏览器|验证码)?验证(?:后继续|后阅读|后访问)?",
        "(?:正在)?(?:检查您的浏览器|检查浏览器|安全验证|人机验证|浏览器验证|验证中)",
        "请(?:开启|启用)(?:JavaScript|Javascript|javascript|JS|js)(?:后重试|后继续|后访问)?",
        "(?:请|请先)?(?:下载|安装|打开|使用)(?:本网站|本站|官方)?(?:APP|App|app|客户端)(?:后|后再)?(?:继续阅读|阅读(?:全文|完整内容)?|查看正文)?",
        "(?:更多精彩内容|完整内容|后续内容|更多内容)(?:请)?(?:下载|打开|前往)(?:APP|App|app|客户端)(?:阅读|查看)?",
        "(?:广告推广|推广链接|商务合作|点击广告|请点击广告|请关闭广告拦截)(?:[：:]?https?://[^<>\\s]+)?",
        "(?:最新网址|最新域名|本站域名|本站网址|请记住本站网址|请牢记本站域名)[：:]https?://[^<>\\s]+",
        "(?:上一章|下一章|上一页|下一页|返回目录|章节目录|返回书页|返回首页|首页|目录)",
        "https?://[^<>\\s]+",
    ).map { Regex(it) }

    fun requireReadable(
        text: String,
        expectedChapterTitle: String = "",
        actualChapterTitle: String = "",
    ): String {
        val expected = chapterTitle(expectedChapterTitle)
        val actual = chapterTitle(actualChapterTitle)
        if (expected.isNotBlank() && actual.isNotBlank() && expected !in setOf("正文", "章节正文")) {
            val match = ReaderSearchPolicy.matchChapter(listOf(NovelChapter(0, actual, "")), expected)
            if (match == null) throw IOException("正文页章节标题与所选目录不一致，未将该来源标记为可读。")
        }

        val headingKeys = setOf(expected, actual).filter(String::isNotBlank)
            .mapTo(hashSetOf(), ReaderBookIdentity::normalize)
        val hasStory = text.lineSequence().map { it.trim() }.filter(String::isNotBlank).any { line ->
            val compact = spacing.replace(line, "")
            compact.any(Char::isLetterOrDigit) && ReaderBookIdentity.normalize(compact) !in headingKeys &&
                !isNotice(compact)
        }
        if (!hasStory) throw IOException("该来源只返回空白、错误或登录验证提示，未取得可阅读正文。")
        return text
    }

    private fun chapterTitle(title: String): String = pageSuffix.replace(title.trim(), "").trim()

    private fun isNotice(value: String): Boolean {
        val compact = noticePrefix.replace(value, "").trimEnd('.', '。', '!', '！', '…')
        if (compact.isBlank()) return true
        if (englishNotice.matches(compact)) return true
        val clauses = clauseBreak.split(compact).map(String::trim).filter(String::isNotBlank)
        return clauses.isNotEmpty() && clauses.all { clause -> noticeClauses.any { it.matches(clause) } }
    }
}
