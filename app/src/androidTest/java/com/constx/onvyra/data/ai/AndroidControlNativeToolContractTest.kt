package com.constx.onvyra.data.ai

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.InputSchema
import me.rerere.ai.provider.Model
import me.rerere.androidcontrol.AndroidControl
import com.constx.onvyra.data.ai.tools.createAndroidControlTools
import com.constx.onvyra.data.files.FilesManager
import org.junit.Assert.*
import org.junit.Test
import org.koin.core.context.GlobalContext

class AndroidControlNativeToolContractTest {
    @Test fun realAppExposesNativeSelectorsAndExplicitVisualFallbackWithoutPaidModelCalls() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        check(Build.HARDWARE in setOf("ranchu","goldfish"))
        check(UiDevice.getInstance(instrumentation).executeShellCommand("getprop ro.boot.qemu.avd_name").trim()=="CodexReaderQA")
        val context=instrumentation.targetContext
        val control=AndroidControl.get(context)
        val before=control.enabled
        try {
            control.enabled=true
            val model=Model()
            val tools=createAndroidControlTools(context,"reader11-contract",model,GlobalContext.get().get<FilesManager>())
            fun schema(name:String)=(tools.single { it.name==name }.parameters() as InputSchema.Obj).properties
            for(name in listOf("tap","input_text","swipe")) {
                assertTrue(schema(name).keys.containsAll(listOf("node_id","target_text","resource_id")))
            }
            assertEquals("boolean",schema("get_ui_tree")["visual_fallback"]!!.jsonObject["type"]!!.jsonPrimitive.content)
            assertTrue(schema("screenshot").containsKey("fallback_reason"))
            val prompt=tools.single { it.name=="open_app" }.systemPrompt(model,emptyList())
            assertTrue(prompt.contains("Default to native accessibility"))
            assertTrue(prompt.contains("do not call screenshot/get_ui_tree again before every action"))
            assertTrue(prompt.contains("run_steps"))
            assertTrue(prompt.contains("user explicitly asks"))
            assertEquals(AndroidControl.TOOLS,tools.map { it.name }.toSet())
        } finally { control.enabled=before }
    }
}
