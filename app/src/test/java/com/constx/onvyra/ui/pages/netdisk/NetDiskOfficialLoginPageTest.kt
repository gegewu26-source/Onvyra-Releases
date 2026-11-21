package com.constx.onvyra.ui.pages.netdisk

import com.constx.onvyra.data.netdisk.NetDiskPlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.mozilla.javascript.Context
import org.mozilla.javascript.Scriptable

class NetDiskOfficialLoginPageTest {
    @Test fun preparesOnlyTheCurrentProvidersActualLoginEntry() {
        assertNotNull(script(NetDiskPlatform.XUNLEI, "https://pan.xunlei.com/login"))
        assertNotNull(script(NetDiskPlatform.GUANGYA, "https://www.guangyapan.com/"))
        assertNull(script(NetDiskPlatform.XUNLEI, "https://pan.xunlei.com/"))
        assertNull(script(NetDiskPlatform.GUANGYA, "https://www.guangyapan.com/#/oauth/login"))
        assertNull(script(NetDiskPlatform.GUANGYA, "https://www.guangyapan.com/#/home"))
        assertNull(script(NetDiskPlatform.GUANGYA, "https://www.guangyapan.com.evil.test/"))
        assertNull(script(NetDiskPlatform.GUANGYA, "https://www.guangyapan.com@evil.test/"))
        assertNull(script(NetDiskPlatform.GUANGYA, "https://www.guangyapan.com:8443/"))
        assertNull(script(NetDiskPlatform.GUANGYA, "http://www.guangyapan.com/"))
        assertNull(NetDiskOfficialLoginPage.preparationScript(NetDiskPlatform.GUANGYA,
            "https://www.guangyapan.com/", accountMode = true))
        NetDiskPlatform.entries.filterNot { it in listOf(NetDiskPlatform.XUNLEI, NetDiskPlatform.GUANGYA) }
            .forEach { assertNull(script(it, "https://www.guangyapan.com/")) }
    }

    @Test fun opensOnlyTheVisiblePublicLauncherOnceAndNeverSubmitsAForm() = withBrowser { context, scope ->
        context.evaluateString(scope, """
            buttons = [control('hidden', '立即登录', false), control('disabled', '立即登录', true, true),
              control('submit', '立即登录', true, false, true), control('send-code', '获取验证码', true),
              control('entry', '立即登录', true), control('other', '登录', true)];
        """.trimIndent(), "fixture", 1, null)
        assertEquals("opened", evaluate(context, scope, guangyaScript))
        assertEquals("opened", evaluate(context, scope, guangyaScript))
        assertEquals("entry", evaluate(context, scope, "clicked.join(',')"))
        assertEquals("ready", evaluate(context, scope, "fields=[control('phone','',true)]; $guangyaScript"))
        assertEquals("entry", evaluate(context, scope, "clicked.join(',')"))
    }

    @Test fun keepsAnAlreadyVisibleOfficialFormAndWaitsForHydrationWithoutClickingUnrelatedButtons() = withBrowser { context, scope ->
        context.evaluateString(scope, """
            fields = [control('phone','',true)]; buttons = [control('entry','立即登录',true)];
        """.trimIndent(), "fixture", 1, null)
        assertEquals("ready", evaluate(context, scope, guangyaScript))
        assertEquals("", evaluate(context, scope, "clicked.join(',')"))
        context.evaluateString(scope, "fields=[]; buttons=[control('submit','登录',true),control('code','发送验证码',true)];",
            "fixture", 1, null)
        assertEquals("waiting", evaluate(context, scope, guangyaScript))
        assertEquals("", evaluate(context, scope, "clicked.join(',')"))
    }

    @Test fun rechecksTheOriginInsideThePageAndWaitsForTheXunleiSsoFrame() = withBrowser { context, scope ->
        context.evaluateString(scope, "location.hostname='www.guangyapan.com.evil.test'; buttons=[control('entry','立即登录',true)];",
            "fixture", 1, null)
        assertEquals("ignored", evaluate(context, scope, guangyaScript))
        assertEquals("", evaluate(context, scope, "clicked.join(',')"))
        context.evaluateString(scope, "location.hostname='pan.xunlei.com'; location.pathname='/login';", "fixture", 1, null)
        val xunlei = script(NetDiskPlatform.XUNLEI, "https://pan.xunlei.com/login")!!
        assertEquals("waiting", evaluate(context, scope, xunlei))
        assertEquals("ready", evaluate(context, scope, "ssoFrame={}; $xunlei"))
        assertEquals("", evaluate(context, scope, "clicked.join(',')"))
    }

    private fun script(platform: NetDiskPlatform, url: String) =
        NetDiskOfficialLoginPage.preparationScript(platform, url, accountMode = false)

    private val guangyaScript get() = script(NetDiskPlatform.GUANGYA, "https://www.guangyapan.com/")!!

    private fun evaluate(context: Context, scope: Scriptable, script: String): String =
        Context.toString(context.evaluateString(scope, script, "official-login", 1, null))

    private fun withBrowser(block: (Context, Scriptable) -> Unit) {
        val context = Context.enter()
        try {
            context.languageVersion = Context.VERSION_ES6
            context.optimizationLevel = -1
            val scope = context.initStandardObjects()
            context.evaluateString(scope, """
                var location={protocol:'https:',hostname:'www.guangyapan.com',pathname:'/',hash:''};
                var clicked=[], buttons=[], fields=[], styles={}, ssoFrame=null;
                function control(id,text,shown,disabled,inForm) {
                  return {textContent:text,disabled:!!disabled,getClientRects:function(){return shown?[{}]:[];},
                    closest:function(){return inForm?{}:null;},click:function(){clicked.push(id);}};
                }
                var window={getComputedStyle:function(){return {display:'block',visibility:'visible',opacity:'1'};}};
                var document={getElementById:function(id){return styles[id]||null;},
                  querySelector:function(selector){return selector==='#login-wrap iframe'?ssoFrame:null;},
                  querySelectorAll:function(selector){return selector.indexOf('input')===0?fields:buttons;},
                  createElement:function(){return {};},head:{appendChild:function(style){styles[style.id]=style;}}};
            """.trimIndent(), "fixture", 1, null)
            block(context, scope)
        } finally { Context.exit() }
    }
}
