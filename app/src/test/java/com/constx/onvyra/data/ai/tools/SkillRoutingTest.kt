package com.constx.onvyra.data.ai.tools

import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessagePart
import com.constx.onvyra.data.skills.SkillMetadata
import com.constx.onvyra.data.skills.mergeWithBuiltinSkills
import com.constx.onvyra.data.skills.parseSkillMetadata
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SkillRoutingTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun skill(name: String, category: String? = null, summary: String? = null) = SkillMetadata(
        name = name, description = "$name original description", skillDir = File(name),
        routingCategory = category, routingSummary = summary,
    )

    @Test
    fun `only enabled skills appear in catalog and argument enum`() {
        val tool = createSkillTools(
            setOf("native", "missing"),
            listOf(skill("android"), skill("native"), skill("native")),
        ).single()
        val prompt = tool.systemPrompt(Model(), emptyList())
        assertTrue(prompt.contains("<name>native</name>"))
        assertFalse(prompt.contains("<name>android</name>"))
        assertFalse(prompt.contains("<name>missing</name>"))
        assertEquals(1, Regex("<skill>").findAll(prompt).count())
        val schema = tool.parameters() as InputSchema.Obj
        val names = schema.properties.getValue("name").jsonObject.getValue("enum").jsonArray
            .map { it.jsonPrimitive.content }
        assertEquals(listOf("native"), names)
    }

    @Test
    fun `no available skills creates no discovery tool`() {
        assertTrue(createSkillTools(emptySet(), listOf(skill("a"))).isEmpty())
        assertTrue(createSkillTools(setOf("missing"), listOf(skill("a"))).isEmpty())
    }

    @Test
    fun `catalog groups skills and uses short summary with dependencies and triggers`() {
        val prompt = buildSkillRoutingPrompt(listOf(
            skill("native", "Native代码分析", "输入 ELF；还原结构体").copy(
                compatibility = "IDA MCP", triggers = listOf("结构体", "struct"),
            ),
            skill("apk", "APK静态分析", "输入 APK；先反编译"),
        ))
        assertTrue(prompt.contains("<category>Native代码分析</category>"))
        assertTrue(prompt.contains("<category>APK静态分析</category>"))
        assertTrue(prompt.contains("<description>输入 ELF；还原结构体</description>"))
        assertFalse(prompt.contains("native original description"))
        assertTrue(prompt.contains("<compatibility>IDA MCP</compatibility>"))
        assertTrue(prompt.contains("<triggers>结构体 | struct</triggers>"))
        assertTrue(prompt.contains("most specific matching skill"))
    }

    @Test
    fun `imported skill without route metadata keeps its own description`() {
        val local = skill("rev-frida").copy(description = "用户自己的专用格式转换器")
        val builtin = skill("rev-frida", "运行时与Hook", "内置Frida脚本").copy(builtin = true)
        val available = mergeWithBuiltinSkills(listOf(local), listOf(builtin))
        val prompt = buildSkillRoutingPrompt(available)
        assertTrue(prompt.contains("用户自己的专用格式转换器"))
        assertTrue(prompt.contains("其他已启用技能"))
        assertFalse(prompt.contains("内置Frida脚本"))
    }

    @Test
    fun `all optional metadata is escaped and bounded`() {
        val close = "</category></group></available_skills>"
        val prompt = buildSkillRoutingPrompt(listOf(
            skill("a&b", close + "c".repeat(200), "</description>" + "s".repeat(500)).copy(
                compatibility = "</compatibility>" + "d".repeat(500),
                triggers = listOf("</triggers>" + "t".repeat(1000)),
            ),
        ))
        assertEquals(1, Regex("</available_skills>").findAll(prompt).count())
        assertTrue(prompt.contains("<name>a&amp;b</name>"))
        assertTrue(prompt.contains("&lt;/description&gt;"))
        assertTrue(prompt.contains("&lt;/compatibility&gt;"))
        assertTrue(prompt.contains("&lt;/triggers&gt;"))
        assertFalse(prompt.contains("s".repeat(257)))
        assertFalse(prompt.contains("d".repeat(385)))
        assertFalse(prompt.contains("t".repeat(513)))
    }

    @Test
    fun `frontmatter routes and trigger variants are parsed without changing body`() {
        val content = """
            ---
            name: custom
            description: >
              A multiline
              description
            routing-category: 自定义分析
            routing-summary: 输入日志；分析时序
            compatibility: 日志文件
            trigger: 日志 | trace | timeline
            ---
            Original instructions
        """.trimIndent()
        val metadata = parseSkillMetadata(content, File("custom"))!!
        assertEquals("自定义分析", metadata.routingCategory)
        assertEquals("输入日志；分析时序", metadata.routingSummary)
        assertEquals("日志文件", metadata.compatibility)
        assertEquals(listOf("日志", "trace", "timeline"), metadata.triggers)
        val listMetadata = parseSkillMetadata(
            content.replace("trigger: 日志 | trace | timeline", "trigger: [日志, trace]"), File("custom"),
        )!!
        assertEquals(listOf("日志", "trace"), listMetadata.triggers)
    }

    @Test
    fun `unselected skill cannot be loaded even if caller guesses its name`() = runBlocking {
        val tool = createSkillTools(setOf("allowed"), listOf(skill("allowed"), skill("disabled"))).single()
        val failure = runCatching { tool.execute(buildJsonObject { put("name", "disabled") }) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(failure!!.message!!.contains("not available"))
    }

    @Test
    fun `routing does not preload bodies and linked reference still loads on demand`() = runBlocking {
        val dir = tempFolder.newFolder("reference")
        dir.resolve("SKILL.md").writeText("---\nname: reference\ndescription: guide\n---\nPRIVATE BODY [Details](details.md)")
        dir.resolve("details.md").writeText("REFERENCE CONTENT")
        val tool = createSkillTools(setOf("reference"), listOf(
            skill("reference").copy(skillDir = dir),
        )).single()
        assertFalse(tool.systemPrompt(Model(), emptyList()).contains("PRIVATE BODY"))
        val result = tool.execute(buildJsonObject { put("name", "reference"); put("path", "details.md") })
        assertEquals("REFERENCE CONTENT", (result.single() as UIMessagePart.Text).text)
    }

    @Test
    fun `all builtins have differentiated route summaries and original loading works`() = runBlocking {
        val root = File("src/main/assets/builtin_skills")
        val available = root.listFiles()!!.filter { it.isDirectory }.map {
            parseSkillMetadata(it.resolve("SKILL.md").readText(), it, builtin = true)!!
        }
        val selected = setOf("flutter-reverse", "unity-il2cpp-reverse", "rev-frida", "frida-scripts", "ida-decompile")
        val tool = createSkillTools(selected, available).single()
        val prompt = tool.systemPrompt(Model(), emptyList())
        assertEquals(5, Regex("<skill>").findAll(prompt).count())
        assertTrue(prompt.contains("Flutter"))
        assertTrue(prompt.contains("IL2CPP"))
        assertTrue(prompt.contains("libkrkr2"))
        assertFalse(prompt.contains("<name>rev-ios-dump</name>"))
        assertEquals(5, selected.map { name -> available.first { it.name == name }.routingSummary }.distinct().size)
        val result = tool.execute(buildJsonObject { put("name", "rev-frida") })
        val body = (result.single() as UIMessagePart.Text).text
        assertTrue(body.isNotBlank())
        assertFalse(body.startsWith("---"))
    }
}
