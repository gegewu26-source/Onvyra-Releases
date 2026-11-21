package com.constx.onvyra.ui.pages.netdisk

import com.constx.onvyra.data.netdisk.NetDiskPlatform
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import org.mozilla.javascript.Context
import org.mozilla.javascript.Scriptable

/** Executes the actual document script with the providers' observed viewport declarations. */
class NetDiskWebZoomPolicyTest {
    private val observedViewports = listOf(
        "width=device-width,initial-scale=1,maximum-scale=1,minimum-scale=1,user-scalable=no,viewport-fit=cover",
        "width=device-width,height=device-height,initial-scale=1,maximum-scale=1,user-scalable=no",
        "viewport-fit=cover,initial-scale=1.0,minimum-scale=1.0,maximum-scale=1.0,user-scalable=no",
    )

    @Test fun actualUcBaiduAnd115RulesPermitZoomWithoutChangingTheirLayout() {
        for (original in observedViewports) withDocument { context, scope ->
            run(context, scope, "metas=[makeMeta('viewport',${quote(original)}),makeMeta('theme-color','#ffffff')];")
            install(context, scope)
            run(context, scope, "flushMutations();")
            val content = run(context, scope, "metas[0].getAttribute('content')")
            assertZoomAllowed(content)
            val current = directives(content)
            directives(original).filterKeys { it !in scaleKeys }.forEach { (key, value) ->
                assertEquals("Layout declaration $key must survive", value, current[key])
            }
            assertEquals("#ffffff", run(context, scope, "metas[1].getAttribute('content')"))
            assertEquals("2", run(context, scope, "metas.length"))
        }
    }

    @Test fun dynamicallyRewrittenAndReplacedViewportTagsAreRepaired() = withDocument { context, scope ->
        run(context, scope, "metas=[makeMeta('viewport',${quote(observedViewports.first())})];")
        install(context, scope)
        run(context, scope, "flushMutations(); metas[0].setAttribute('content','width=1280,initial-scale=0.8,user-scalable=no,maximum-scale=1'); flushMutations();")
        var content = run(context, scope, "metas[0].getAttribute('content')")
        assertZoomAllowed(content)
        assertEquals("1280", directives(content)["width"])
        assertEquals("0.8", directives(content)["initial-scale"])
        run(context, scope, "metas=[]; document.head.appendChild(makeMeta('viewport','width=980,initial-scale=0.75,minimum-scale=1,maximum-scale=1,user-scalable=no')); flushMutations();")
        content = run(context, scope, "metas[0].getAttribute('content')")
        assertZoomAllowed(content)
        assertEquals("980", directives(content)["width"])
        assertEquals("0.75", directives(content)["initial-scale"])
        assertEquals("1", run(context, scope, "observers.length"))
    }

    @Test fun missingHeadIsHandledAfterInsertionWithoutInventingADeviceWidth() = withDocument { context, scope ->
        run(context, scope, "document.head=null;")
        install(context, scope)
        assertEquals("0", run(context, scope, "metas.length"))
        run(context, scope, "document.head=head; queueMutation('childList'); flushMutations();")
        assertEquals("1", run(context, scope, "metas.length"))
        val content = run(context, scope, "metas[0].getAttribute('content')")
        assertZoomAllowed(content)
        assertFalse(directives(content).containsKey("width"))
        assertFalse(directives(content).containsKey("initial-scale"))
    }

    @Test fun repeatedInitializationAndMultipleMetasSettleWithoutMutationLoops() = withDocument { context, scope ->
        run(context, scope, "metas=[makeMeta('viewport',${quote(observedViewports[0])}),makeMeta('VIEWPORT',${quote(observedViewports[1])}),makeMeta('theme-color','#fff')];")
        install(context, scope)
        run(context, scope, "flushMutations();")
        assertZoomAllowed(run(context, scope, "metas[0].getAttribute('content')"))
        assertZoomAllowed(run(context, scope, "metas[1].getAttribute('content')"))
        val writes = run(context, scope, "attributeWrites")
        repeat(3) { install(context, scope) }
        assertEquals("0", run(context, scope, "flushMutations()"))
        assertEquals(writes, run(context, scope, "attributeWrites"))
        assertEquals("1", run(context, scope, "observers.length"))
        assertEquals("3", run(context, scope, "metas.length"))
        run(context, scope, "metas[0].setAttribute('content',metas[0].getAttribute('content'));")
        assertEquals("1", run(context, scope, "flushMutations()"))
        assertEquals((writes.toInt() + 1).toString(), run(context, scope, "attributeWrites"))
        assertEquals("#fff", run(context, scope, "metas[2].getAttribute('content')"))
    }

    @Test fun mixedCaseAndSemicolonRulesKeepUnknownViewportOptions() = withDocument { context, scope ->
        val original = "WIDTH=1024; initial-scale=0.8; USER-SCALABLE=NO; minimum-SCALE=1; maximum-scale=1; maximum-scale=2; viewport-fit=cover; interactive-widget=resizes-content"
        run(context, scope, "metas=[makeMeta('ViewPort',${quote(original)})];")
        install(context, scope)
        run(context, scope, "flushMutations();")
        val content = run(context, scope, "metas[0].getAttribute('content')")
        assertZoomAllowed(content)
        assertTrue(content.contains("WIDTH=1024"))
        assertEquals("0.8", directives(content)["initial-scale"])
        assertEquals("cover", directives(content)["viewport-fit"])
        assertEquals("resizes-content", directives(content)["interactive-widget"])
        for (key in scaleKeys) assertEquals(1, content.split(',').count {
            it.substringBefore('=').trim().equals(key, ignoreCase = true)
        })
    }

    @Test fun onlyTheThreeProvidersObservedToLockZoomInstallTheOverride() {
        assertEquals(setOf(NetDiskPlatform.UC, NetDiskPlatform.BAIDU, NetDiskPlatform.PAN115),
            NetDiskPlatform.entries.filter { NetDiskWebZoomPolicy.origins(it).isNotEmpty() }.toSet())
        assertTrue(NetDiskWebZoomPolicy.origins(NetDiskPlatform.QUARK).isEmpty())
        for (platform in NetDiskPlatform.entries) for (origin in NetDiskWebZoomPolicy.origins(platform)) {
            assertTrue(origin.startsWith("https://"))
            assertFalse(origin == "*" || origin == "https://*")
        }
    }

    private val scaleKeys = setOf("user-scalable", "minimum-scale", "maximum-scale")
    private fun directives(value: String): Map<String, String> = value.split(',', ';').filter(String::isNotBlank)
        .associate { it.substringBefore('=').trim().lowercase() to it.substringAfter('=', "").trim() }
    private fun assertZoomAllowed(value: String) {
        val options = directives(value)
        assertEquals("yes", options["user-scalable"])
        assertTrue(options.getValue("minimum-scale").toDouble() < 1.0)
        assertTrue(options.getValue("maximum-scale").toDouble() > 1.0)
    }
    private fun quote(value: String): String = JsonPrimitive(value).toString()
    private fun install(context: Context, scope: Scriptable) = run(context, scope, NetDiskWebZoomPolicy.DOCUMENT_SCRIPT)
    private fun run(context: Context, scope: Scriptable, script: String): String =
        Context.toString(context.evaluateString(scope, script, "viewport-zoom", 1, null))

    private fun withDocument(block: (Context, Scriptable) -> Unit) {
        val context = Context.enter()
        try {
            context.languageVersion = Context.VERSION_ES6
            context.optimizationLevel = -1
            val scope = context.initStandardObjects()
            context.evaluateString(scope, """
                var metas=[],observers=[],pending=false,attributeWrites=0;
                function queueMutation(type,name){
                  for(var i=0;i<observers.length;i++){
                    var o=observers[i],opts=o.options;
                    if(!opts)continue;
                    if(type==='childList'&&opts.childList)pending=true;
                    if(type==='attributes'&&opts.attributes&&(!opts.attributeFilter||opts.attributeFilter.indexOf(name)>=0))pending=true;
                  }
                }
                function makeMeta(name,content){
                  return {attrs:{name:name,content:content},getAttribute:function(key){return this.attrs[key]||null;},
                    setAttribute:function(key,value){this.attrs[key]=String(value);attributeWrites++;queueMutation('attributes',key);}};
                }
                var head={appendChild:function(element){metas.push(element);queueMutation('childList');}};
                var document={head:head,querySelectorAll:function(selector){
                    if(selector!=='meta')throw Error('Only viewport metadata may be inspected');return metas.slice();},
                  createElement:function(tag){if(tag!=='meta')throw Error('Unexpected element');return makeMeta('','');}};
                var window={};
                Object.defineProperty(document,'cookie',{get:function(){throw Error('Credentials must not be read');}});
                Object.defineProperty(document,'forms',{get:function(){throw Error('Forms must not be read');}});
                Object.defineProperty(window,'localStorage',{get:function(){throw Error('Storage must not be read');}});
                Object.defineProperty(window,'sessionStorage',{get:function(){throw Error('Storage must not be read');}});
                function MutationObserver(callback){this.callback=callback;observers.push(this);}
                MutationObserver.prototype.observe=function(target,options){
                  if(target!==document)throw Error('Observe the document before head exists');this.options=options;};
                function flushMutations(){
                  var turns=0;
                  while(pending){if(++turns>20)throw Error('Observer mutation loop');pending=false;
                    var current=observers.slice();for(var i=0;i<current.length;i++)if(current[i].options)current[i].callback([]);}
                  return turns;
                }
            """.trimIndent(), "fixture", 1, null)
            block(context, scope)
        } finally { Context.exit() }
    }
}
