package com.constx.onvyra.ui.pages.novel

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Original bundled rule plus independently produced protocol responses; no live credentials or network. */
class LegadoYuluRuntimeCompatibilityTest {
    @Test fun duplicateDnsFailureLetsAnUnchangedRuleReachItsHealthyMirror() = runBlocking {
        var failedCalls = 0
        var liveCalls = 0
        val runtime = LegadoRuleRuntime(ajax = { request ->
            if (request.startsWith("https://unresolved.example/")) {
                failedCalls++
                throw IOException("transport failed", UnknownHostException())
            }
            liveCalls++
            "healthy"
        })
        val script = """@js:(function(){
            try{java.ajax('https://unresolved.example/config')}catch(e){}
            try{java.connect('https://unresolved.example/config').body()}catch(e){}
            return java.ajax('https://healthy.example/config');
        })()"""
        assertEquals("healthy", runtime.string("", script, base))
        assertEquals(1, failedCalls)
        assertEquals(1, liveCalls)
        // The suppression is scoped to one script turn, so later network recovery can retry.
        assertEquals("healthy", runtime.string("", script, base))
        assertEquals(2, failedCalls)
    }
    private val base = "https://s.wendulou.com/v6/book/search?form=1"
    private val rule by lazy {
        val path = listOf(File("src/main/assets/reader/book-sources-489.json"),
            File("app/src/main/assets/reader/book-sources-489.json")).first(File::isFile)
        Json.parseToJsonElement(path.readText()).jsonArray.first {
            it.jsonObject["bookSourceName"]?.jsonPrimitive?.content == "雨鹿小说"
        }.jsonObject
    }
    private val library get() = rule.getValue("jsLib").jsonPrimitive.content
    private fun literal(value: String) = JsonPrimitive(value).toString()
    private fun encrypt(value: String, key: ByteArray, iv: ByteArray) = Cipher.getInstance("AES/CBC/PKCS5Padding").run {
        init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        doFinal(value.toByteArray(Charsets.UTF_8))
    }
    private fun envelope(value: String): String {
        val prefix = "0123456789abcdef".toByteArray(Charsets.US_ASCII)
        val suffix = "fedcba9876543210".toByteArray(Charsets.US_ASCII)
        val key = MessageDigest.getInstance("SHA-256").digest(prefix)
        val ascii = MessageDigest.getInstance("MD5").digest(suffix).joinToString("") { "%02x".format(it) }
            .toByteArray(Charsets.US_ASCII)
        val iv = ByteArray(16) { (ascii[it].toInt() xor suffix[it].toInt()).inv().toByte() }
        return buildJsonObject {
            put("ver", 1)
            put("content", Base64.getEncoder().encodeToString(prefix + encrypt(value, key, iv) + suffix))
        }.toString()
    }
    private fun fixed(value: String): String {
        fun constant(name: String) = Regex("var $name = \"([^\"]+)\";").find(library)!!.groupValues[1]
            .toByteArray(Charsets.UTF_8)
        return Base64.getEncoder().encodeToString(encrypt(value, constant("YULU_FIXED_KEY"), constant("YULU_FIXED_IV")))
    }

    @Test fun digestAndSignedByteConversionsMatchStandardCrypto() = runBlocking {
        val runtime = LegadoRuleRuntime()
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            runtime.string("", "@js:java.digestHex('abc','SHA-256')", base))
        assertEquals("中文", runtime.string("", "@js:java.bytesToStr(java.strToBytes('中文','UTF-8'),'UTF-8')", base))
        assertEquals("AP+A", runtime.string("", "@js:java.base64Encode(java.hexDecodeToByteArray('00ff80'))", base))
        assertEquals("0,-1,-128", runtime.string("", "@js:java.base64DecodeToByteArray('AP+A').join(',')", base))
    }

    @Test fun importedDynamicEnvelopeReallyDecryptsWithoutChangingItsScript() = runBlocking {
        val expected = "独立生成的动态协议数据：中文与 ASCII"
        val runtime = LegadoRuleRuntime(jsLib = library)
        assertEquals(expected, runtime.string(envelope(expected), "@js:yuluDecryptEnvelope(java,result)", base))
    }

    @Test fun realSearchRuleDecodesAndReturnsItsBookObjects() = runBlocking {
        val data = """{"book":[{"book_id":7001,"name":"协议书名","author":"协议作者","intro":"协议简介","cover":"/cover/7001.jpg"}],"author":[]}"""
        val runtime = LegadoRuleRuntime(jsLib = library)
        val listRule = rule.getValue("ruleSearch").jsonObject.getValue("bookList").jsonPrimitive.content
        val rows = runtime.elements(envelope(data), listRule, base)
        assertEquals(1, rows.size)
        assertEquals("协议书名", runtime.string(rows[0], "name", base))
        assertEquals("协议作者", runtime.string(rows[0], "author", base))
        assertTrue(runtime.string(rows[0], "detail_url", base).contains("7001"))
    }

    @Test fun realChapterAndContentRulesUseFixedCipherAndSourceAdReplacements() = runBlocking {
        val state = ConcurrentHashMap<String, String>()
        state["yulu_site_regulars"] = """{"fixture-site":[{"regular_key":"广告占位","regular_value":""}]}"""
        val runtime = LegadoRuleRuntime(jsLib = library, sourceVariables = state)
        val chapters = buildJsonObject { put("code", 1); put("data", buildJsonArray {
            add(buildJsonObject { put("name", fixed("第一章 起始")); put("path", "/fixture/1") })
        }) }.toString()
        val chapterRule = rule.getValue("ruleToc").jsonObject.getValue("chapterList").jsonPrimitive.content
        val rows = runtime.elements(chapters, chapterRule, "https://catalog.wendulou.com/fixture?yulu_site_id=fixture-site")
        assertEquals(1, rows.size)
        assertEquals("第一章 起始", runtime.string(rows.single(), "title", base))
        val chapterUrl = runtime.string(rows.single(), "url", base)
        assertTrue(chapterUrl.contains("yulu_site_id=fixture-site"))
        val body = buildJsonObject { put("code", 1); put("data", buildJsonObject {
            put("content", fixed("正常正文。广告占位继续正文。"))
        }) }.toString()
        val contentRule = rule.getValue("ruleContent").jsonObject.getValue("content").jsonPrimitive.content
        assertEquals("正常正文。继续正文。", runtime.string(body, contentRule, chapterUrl))
    }

    @Test fun sourceStateSurvivesSessionsOnlyWhenTheSameSourceOwnsTheStore() = runBlocking {
        val shared = ConcurrentHashMap<String, String>()
        LegadoRuleRuntime(sourceVariables = shared).string("", "@js:source.put('domain','fixture.example')", base)
        assertEquals("fixture.example", LegadoRuleRuntime(sourceVariables = shared)
            .string("", "@js:source.get('domain')", base))
        assertEquals("", LegadoRuleRuntime(sourceVariables = ConcurrentHashMap())
            .string("", "@js:source.get('domain')", base))
        assertEquals("fixture.example", LegadoRuleRuntime(sourceVariables = shared)
            .string("", "@js:source.getVariable('domain')", base))
    }

    @Test fun connectUsesTheExistingTransportAndMergesExplicitHeaders() = runBlocking {
        var request = ""
        val runtime = LegadoRuleRuntime(ajax = { request = it; "{\"answer\":7}" })
        assertEquals("200:7", runtime.string("", """@js:
            var response=java.connect('https://fixture.example/api,{"method":"GET","headers":{"X-Old":"old"}}',
                '{"X-New":"new"}'); response.code()+':'+JSON.parse(response.body()).answer;
        """.trimIndent(), base))
        val options = Json.parseToJsonElement(request.substringAfter(",")).jsonObject
        assertEquals("https://fixture.example/api", request.substringBefore(","))
        assertEquals("GET", options.getValue("method").jsonPrimitive.content)
        assertEquals("old", options.getValue("headers").jsonObject.getValue("X-Old").jsonPrimitive.content)
        assertEquals("new", options.getValue("headers").jsonObject.getValue("X-New").jsonPrimitive.content)
    }

    @Test fun limitedConstructorsNeverExposeClassesFilesOrReflection() = runBlocking {
        val runtime = LegadoRuleRuntime()
        assertEquals("2:7:-1", runtime.string("", """@js:
            var list=new Packages.java.util.ArrayList();list.add(7);list.add(9);
            var bytes=new Packages.java.io.ByteArrayOutputStream(1);bytes.write(255);
            list.size()+':'+list.get(0)+':'+bytes.toByteArray()[0]
        """.trimIndent(), base))
        assertEquals("undefined:undefined:undefined:undefined", runtime.string("",
            "@js:typeof Packages.java.lang+':'+typeof Packages.java.io.File+':'+typeof java.getClass+':'+typeof JavaAdapter", base))
        try {
            runtime.string("", "@js:new Packages.java.io.ByteArrayOutputStream(1000000000)", base)
            fail("Oversized byte allocation should fail")
        } catch (_: LegadoRuleException) { }
    }

    @Test(timeout = 10_000) fun networkWaitingDoesNotConsumeThePureJavascriptDeadline() = runBlocking {
        val runtime = LegadoRuleRuntime(ajax = { Thread.sleep(3_100); "ok" })
        assertEquals("ok:199990000", runtime.string("", "@js:var text=java.ajax('https://fixture.example/');var total=0;for(var i=0;i<20000;i++)total+=i;text+':'+total", base))
    }
}
