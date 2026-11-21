package com.constx.onvyra.data.skills

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltinSkillsTest {
    @Test
    fun `local skill overrides builtin skill with same name`() {
        val local = SkillMetadata(name = "shared", description = "local", skillDir = File("skills/shared"))
        val merged = mergeWithBuiltinSkills(
            local = listOf(local),
            builtin = listOf(
                SkillMetadata(name = "shared", description = "builtin", skillDir = File("builtin/shared"), builtin = true),
                SkillMetadata(name = "only-builtin", description = "builtin", skillDir = File("builtin/only"), builtin = true),
            ),
        )

        assertEquals(listOf("shared", "only-builtin"), merged.map { it.name })
        assertEquals(local, merged.first())
    }

    @Test
    fun `builtin skills in assets are valid`() {
        // Android 单元测试的工作目录是模块根目录
        val root = File("src/main/assets/builtin_skills")
        val skillDirs = root.listFiles()?.filter { it.isDirectory }.orEmpty()
        assertTrue("No builtin skills found in ${root.absolutePath}", skillDirs.isNotEmpty())

        skillDirs.forEach { dir ->
            val skillFile = dir.resolve("SKILL.md")
            assertTrue("${dir.name}: missing SKILL.md", skillFile.exists())
            val content = skillFile.readText()
            val frontmatter = SkillFrontmatterParser.parse(content)
            assertEquals("${dir.name}: name must match directory name", dir.name, frontmatter["name"])
            val description = frontmatter["description"]
            assertTrue("${dir.name}: description is blank", !description.isNullOrBlank())
            assertTrue("${dir.name}: description exceeds 1024 chars", description!!.length <= 1024)

            // 正文中的相对链接必须指向技能目录内存在的文件（忽略代码块和行内代码中的示例）
            val body = SkillFrontmatterParser.extractBody(content)
                .replace(Regex("(?s)```.*?```"), "")
                .replace(Regex("`[^`]*`"), "")
            Regex("""\]\(([^)\s]+)\)""").findAll(body)
                .map { it.groupValues[1].substringBefore('#') }
                .filter { it.isNotBlank() && !it.contains("://") && !it.startsWith("/") }
                .forEach { link ->
                    assertTrue("${dir.name}: broken link $link", dir.resolve(link).exists())
                }
        }
    }

    @Test
    fun `every builtin skill has a concise task routing summary`() {
        val root = File("src/main/assets/builtin_skills")
        val categories = setOf(
            "任务规划与报告", "APK静态分析", "运行时与Hook", "Native代码分析", "加固与还原",
            "框架专项", "Android构建开发", "Web/API测试", "技能与Agent开发",
        )
        val skillDirs = root.listFiles()?.filter { it.isDirectory }.orEmpty()
        assertEquals("Every packaged skill needs routing metadata", 48, skillDirs.size)
        val summaries = skillDirs.map { dir ->
            val metadata = SkillFrontmatterParser.parse(dir.resolve("SKILL.md").readText())
            assertTrue("${dir.name}: missing or unknown routing category", metadata["routing-category"] in categories)
            val summary = metadata["routing-summary"].orEmpty()
            assertTrue("${dir.name}: routing summary must contain 1 to 200 characters", summary.length in 1..200)
            assertFalse("${dir.name}: routing summary must fit one catalog entry", summary.contains('\n'))
            summary
        }
        assertEquals("Different skills need distinct routing summaries", summaries.size, summaries.distinct().size)
    }

    @Test
    fun `xuanxing catalog includes every supporting asset and keeps original licenses`() {
        val root = File("src/main/assets/builtin_skills")
        val catalog = Json.parseToJsonElement(root.resolve("catalog.json").readText()).jsonObject
        val imports = catalog.getValue("importedSkills").jsonArray
        assertTrue("The XuanXing library is empty", imports.isNotEmpty())

        val names = imports.map { it.jsonObject.getValue("name").jsonPrimitive.content }
        assertEquals("Duplicate skill names", names.distinct().size, names.size)
        assertTrue("Android analysis skill is missing", "android-reverse-engineering" in names)
        assertTrue("MT MCP skill is missing", "mt-mcp-apk-analyzer" in names)
        assertTrue("Existing skill-creator was removed", root.resolve("skill-creator/SKILL.md").isFile)
        assertTrue("Source license was removed", root.resolve("LICENSE-XuanXing.txt").isFile)

        imports.forEach { entry ->
            val skill = entry.jsonObject
            val dir = root.resolve(skill.getValue("name").jsonPrimitive.content)
            skill.getValue("packagedFiles").jsonArray.forEach { asset ->
                assertTrue("${dir.name}: missing supporting asset $asset", dir.resolve(asset.jsonPrimitive.content).isFile)
            }
            skill.getValue("licenseFiles").jsonArray.forEach { license ->
                assertTrue("${dir.name}: missing license $license", dir.resolve(license.jsonPrimitive.content).isFile)
            }
            val metadata = SkillFrontmatterParser.parse(dir.resolve("SKILL.md").readText())
            assertFalse("${dir.name}: missing dependency guidance", metadata["compatibility"].isNullOrBlank())
        }

        catalog.getValue("excludedSkills").jsonObject.keys.forEach { name ->
            assertFalse("Excluded skill was packaged: $name", root.resolve(name).exists())
        }
        assertFalse("Generated bytecode was packaged", root.walkTopDown().any { it.extension == "pyc" })
    }

    @Test
    fun `curated phone pack preserves source provenance and linked text assets`() {
        val root = File("src/main/assets/builtin_skills")
        val catalog = Json.parseToJsonElement(root.resolve("catalog.json").readText()).jsonObject
        val library = catalog.getValue("additionalLibraries").jsonArray.single().jsonObject
        assertTrue(root.resolve("NOTICE-Pentest-Skills.md").isFile)
        assertTrue(library.getValue("archiveSha256").jsonPrimitive.content.matches(Regex("[a-f0-9]{64}")))
        val sources = library.getValue("selectedSourceSkills").jsonArray
        assertEquals(16, sources.size)
        sources.forEach { source ->
            assertTrue(source.jsonObject.getValue("sourceSkillSha256").jsonPrimitive.content.matches(Regex("[a-f0-9]{64}")))
        }
        val entries = library.getValue("skills").jsonArray
        assertEquals(4, entries.size)
        entries.forEach { entry ->
            val skill = entry.jsonObject
            val dir = root.resolve(skill.getValue("name").jsonPrimitive.content)
            val files = skill.getValue("packagedFiles").jsonArray.map { it.jsonPrimitive.content }.toSet()
            val actual = dir.walkTopDown().filter { it.isFile }.map { it.relativeTo(dir).invariantSeparatorsPath }.toSet()
            assertEquals("Packaged file inventory must be complete", files, actual)
            assertTrue("Only reviewed Markdown is imported", files.all { it.endsWith(".md") })
            files.forEach { path ->
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(dir.resolve(path).readBytes()).joinToString("") { "%02x".format(it.toInt() and 0xff) }
                assertEquals(skill.getValue("packagedSha256").jsonObject.getValue(path).jsonPrimitive.content, digest)
            }
            val body = dir.resolve("SKILL.md").readText()
            val links = Regex("""\]\((references/[^)\s]+)\)""").findAll(body)
                .map { it.groupValues[1] }.toSet()
            assertEquals("References must be discoverable through use_skill", files - "SKILL.md", links)
        }
    }

}
