package com.constx.onvyra.ui.pages.novel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/** No app entry point or network access; used by instrumentation on the installed APK. */
internal object ReaderRuntimeCompatibilityProbe {
    @JvmStatic
    fun verify(): String = runBlocking(Dispatchers.IO) {
        val runtime = LegadoRuleRuntime()
        val base = "https://example.invalid/"
        check(runtime.string("<h1>reader-title</h1>", "@js:java.getString('h1@text')", base) == "reader-title")
        check(runtime.string("", "@js:JSON.parse('{\"value\":2}').value", base) == "2")
        check(runtime.string("", "@js:/a+/.test('aaa')", base) == "true")
        check(runtime.string("", "@js:new Uint8Array([1,2])[1]", base) == "2")
        check(runtime.string("故事正文广告尾巴", "##广告尾巴", base) == "故事正文")
        check(runtime.template("", "https://example.invalid/?page={{page+1}}", base,
            mapOf("page" to "1")) == "https://example.invalid/?page=2")

        // Confirm a runtime linkage failure is contained by the same production
        // rule boundary rather than becoming an uncaught Android process crash.
        val missingRuntime = LegadoRuleRuntime(ajax = { throw NoClassDefFoundError("reader-probe") })
        val failure = runCatching { missingRuntime.string("", "@js:java.ajax('unused')", base) }.exceptionOrNull()
        check(failure is LegadoRuleException && generateSequence<Throwable>(failure) { it.cause }.take(12).any { it is LinkageError })
        "reader-js-optimized-ok"
    }
}
