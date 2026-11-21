package com.constx.onvyra.data.netdisk

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

/** Pinned YunGet personal-directory contracts; fixtures never contact a real account. */
class LanzouAccountBrowserTest {
    @Test fun officialBootstrapUsesTheSavedCookieAndBothRootListTasksWithoutCreatingShares() = runBlocking {
        val tasks = mutableListOf<String>()
        val api = browser { request ->
            assertEquals("pc.woozooo.com", request.url.host)
            assertEquals(COOKIE, request.header("Cookie"))
            assertEquals("https://pc.woozooo.com", request.header("Origin"))
            assertEquals("https://pc.woozooo.com/mydisk.php", request.header("Referer"))
            when (request.url.encodedPath) {
                "/mydisk.php" -> {
                    assertEquals("GET", request.method)
                    assertEquals("files", request.url.queryParameter("item"))
                    assertEquals("index", request.url.queryParameter("action"))
                    200 to BOOTSTRAP
                }
                "/doupload.php" -> {
                    assertEquals("POST", request.method)
                    assertEquals("123456", request.url.queryParameter("uid"))
                    assertEquals("a+B/c==", request.url.queryParameter("vei"))
                    assertEquals("XMLHttpRequest", request.header("X-Requested-With"))
                    val form = form(request)
                    assertEquals("-1", form["folder_id"])
                    tasks += form.getValue("task")
                    when (form["task"]) {
                        "47" -> {
                            assertEquals(setOf("task", "folder_id"), form.keys)
                            200 to """{"zt":"1","text":[{"fol_id":"42","name":"资料","time":"2026-11-13"}]}"""
                        }
                        "5" -> {
                            assertEquals(setOf("task", "folder_id", "pg"), form.keys)
                            assertEquals("1", form["pg"])
                            200 to """{"zt":1,"text":[{"id":"42","name":"short...","name_all":"完整文件名.txt","size":"1.5 M","time":"2026-11-14"}]}"""
                        }
                        else -> throw AssertionError("Only read-only tasks 47 and 5 are authorized")
                    }
                }
                else -> throw AssertionError("Unexpected endpoint")
            }
        }
        val result = api.list(COOKIE)
        assertEquals(listOf("47", "5"), tasks)
        assertEquals("我的文件", result.title)
        assertEquals(listOf("d:42", "f:42"), result.files.map(NetDiskFile::id))
        assertTrue(result.files.first().isDirectory)
        assertEquals("完整文件名.txt", result.files.last().name)
        assertEquals(1572864L, result.files.last().size)
        assertEquals("-1", result.files.first().extra["parent_id"]?.jsonPrimitive?.content)
        assertNotNull(result.nextCursor) // Even a short nonempty zt=1 page can have a following page.
    }

    @Test fun navigatingFoldersUsesTheirOwnIdAndPaginationAddsFilesWithoutRepeatingFolders() = runBlocking {
        val requests = mutableListOf<Pair<String, Map<String, String>>>()
        val api = browser { request ->
            if (request.url.encodedPath == "/mydisk.php") 200 to BOOTSTRAP else {
                val values = form(request)
                val parent = values.getValue("folder_id")
                requests += parent to values
                when {
                    parent == "-1" && values["task"] == "47" ->
                        200 to """{"zt":1,"text":[{"fol_id":"42","name":"一级目录"},{"fol_id":"51","name":"别处的目录","parent_id":"999"}]}"""
                    parent == "-1" && values["task"] == "5" -> 200 to """{"zt":2,"text":[]}"""
                    parent == "42" && values["task"] == "47" ->
                        200 to """{"zt":1,"text":[{"fol_id":"43","name":"二级目录","parent_id":"42"}]}"""
                    parent == "42" && values["pg"] == "1" ->
                        200 to """{"zt":1,"text":[{"id":"71","name_all":"第1页.txt","size":"10 B"}]}"""
                    parent == "42" && values["pg"] == "2" ->
                        200 to """{"zt":1,"text":[{"id":"72","name_all":"第2页.txt","size":"20 B"}]}"""
                    parent == "42" && values["pg"] == "3" -> 200 to """{"zt":2,"text":""}"""
                    else -> throw AssertionError("Unexpected directory or page")
                }
            }
        }
        val root = api.list(COOKIE)
        assertEquals(listOf("d:42"), root.files.map(NetDiskFile::id))
        val first = api.list(COOKIE, root.files.single().id)
        assertEquals(listOf("d:43", "f:71"), first.files.map(NetDiskFile::id))
        assertNull(first.title)
        val second = api.list(COOKIE, "d:42", first.nextCursor)
        assertEquals(listOf("f:72"), second.files.map(NetDiskFile::id))
        val end = api.list(COOKIE, "d:42", second.nextCursor)
        assertTrue(end.files.isEmpty())
        assertNull(end.nextCursor)
        assertEquals(1, requests.count { it.first == "42" && it.second["task"] == "47" })
        assertEquals(listOf("1", "2", "3"), requests.filter { it.first == "42" && it.second["task"] == "5" }.map { it.second["pg"] })
    }

    @Test fun ztTwoRetainsAnyFinalRecordsAndStopsWithoutRelyingOnAnInventedPageSize() = runBlocking {
        val api = browser { request ->
            if (request.url.encodedPath == "/mydisk.php") 200 to BOOTSTRAP
            else if (form(request)["task"] == "47") 200 to """{"zt":2}"""
            else 200 to """{"zt":2,"text":[{"id":"88","name":"last.bin","size":"2048"}]}"""
        }
        val result = api.list(COOKIE, "-1")
        assertEquals("f:88", result.files.single().id)
        assertEquals(2048L, result.files.single().size)
        assertNull(result.nextCursor)
    }

    @Test fun missingCredentialsAndInvalidIdsOrCursorsFailBeforeSendingAnyAccountRequest() = runBlocking {
        var calls = 0
        val api = browser { calls++; 200 to BOOTSTRAP }
        for (cookie in listOf("", "  ", ";;", "not-a-cookie", "session=x\r\nInjected: secret"))
            failure("auth_required") { api.list(cookie) }
        for (id in listOf("f:42", "d:../42", "https://example.invalid/", "-2"))
            failure("invalid_directory") { api.list(COOKIE, id) }
        for (cursor in listOf("", "2", "lz:42:1:0123456789abcdef", "lz:99:2:0123456789abcdef"))
            failure("invalid_cursor") { api.list(COOKIE, "d:42", cursor) }
        assertEquals(0, calls)
    }

    @Test fun loginAndExpiredAccountResponsesNeverBecomeSuccessfulEmptyDirectories() = runBlocking {
        val login = browser { 200 to "<form action='accounts.php?action=login'><input name='password'></form>" }
        failure("auth_required") { login.list(COOKIE) }
        for (status in listOf(302, 401, 403)) {
            val rejected = browser { status to "" }
            failure("auth_required") { rejected.list(COOKIE) }
        }
        val expired = browser { request ->
            if (request.url.encodedPath == "/mydisk.php") 200 to BOOTSTRAP else 200 to """{"zt":9,"text":"expired"}"""
        }
        failure("auth_required") { expired.list(COOKIE) }
        val loginInsteadOfJson = browser { request ->
            if (request.url.encodedPath == "/mydisk.php") 200 to BOOTSTRAP
            else 200 to "<form><input name='password'></form>"
        }
        failure("auth_required") { loginInsteadOfJson.list(COOKIE) }
    }

    @Test fun missingArraysMalformedEntriesAndProviderFailuresRemainVisible() = runBlocking {
        for (body in listOf("{}", """{"zt":1}""", """{"zt":1,"text":{}}""", """{"zt":1,"text":[{"name":"missing id"}]}""", "not-json")) {
            val api = browser { request -> if (request.url.encodedPath == "/mydisk.php") 200 to BOOTSTRAP else 200 to body }
            failure(if (body == "{}") "provider_error" else "invalid_response") { api.list(COOKIE) }
        }
        for ((status, body, reason) in listOf(
            Triple(429, "", "rate_limited"),
            Triple(200, """{"zt":4}""", "rate_limited"),
            Triple(500, "", "provider_error"),
        )) {
            val api = browser { request -> if (request.url.encodedPath == "/mydisk.php") 200 to BOOTSTRAP else status to body }
            failure(reason) { api.list(COOKIE) }
        }
    }

    @Test fun repeatedFilePagesStopRatherThanLoopingOrFlatteningAnotherDirectory() = runBlocking {
        val api = browser { request ->
            if (request.url.encodedPath == "/mydisk.php") 200 to BOOTSTRAP
            else if (form(request)["task"] == "47") 200 to """{"zt":2,"text":[]}"""
            else 200 to """{"zt":1,"text":[{"id":"71","name":"sample.txt"}]}"""
        }
        val first = api.list(COOKIE, "d:42")
        failure("invalid_cursor") { api.list(COOKIE, "d:43", first.nextCursor) }
        failure("pagination_loop") { api.list(COOKIE, "d:42", first.nextCursor) }
    }

    @Test fun accountDownloadRequiresTheOfficialFilePageAndNeverCreatesAPublicShare() = runBlocking {
        var calls = 0
        val api = browser { calls++; throw AssertionError("No personal download endpoint is authorized to create a share") }
        failure("account_browser_required") { api.resolve(NetDiskFile("f:71", "sample.txt"), COOKIE) }
        failure("is_directory") { api.resolve(NetDiskFile("d:42", "folder", isDirectory = true), COOKIE) }
        assertEquals(0, calls)
    }

    private fun browser(answer: (Request) -> Pair<Int, String>): LanzouAccountBrowser {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val (status, body) = answer(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("fixture")
                .body(body.toResponseBody()).build()
        }.build()
        return LanzouAccountBrowser(NetDiskHttp(client))
    }

    private fun form(request: Request): Map<String, String> = (request.body as FormBody).let { body ->
        (0 until body.size).associate { body.name(it) to body.value(it) }
    }

    private suspend fun failure(reason: String, block: suspend () -> Unit) {
        try { block(); fail("Expected $reason") }
        catch (error: NetDiskException) { assertEquals(reason, error.reason) }
    }

    private companion object {
        const val COOKIE = "ylogin=fixture; phpdisk_info=fixture; PHPSESSID=fixture"
        const val BOOTSTRAP = """<html><script>$.ajax({url:'/doupload.php?uid=123456',data:{'vei':'a+B/c=='}});</script></html>"""
    }
}
