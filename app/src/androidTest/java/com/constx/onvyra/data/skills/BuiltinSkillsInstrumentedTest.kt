package com.constx.onvyra.data.skills

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import com.constx.onvyra.data.ai.tools.createSkillTools
import com.constx.onvyra.data.datastore.SettingsStore
import me.rerere.workspace.WorkspaceManager
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.java.KoinJavaComponent.getKoin

/** Exercises assets from the installed APK without API calls or writes to the user's skill folder. */
@RunWith(AndroidJUnit4::class)
class BuiltinSkillsInstrumentedTest {
    @Test
    fun packagedSkillsExtractAndLoadThroughTheAgentTool() = runBlocking {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val scratch = File(appContext.cacheDir, "builtin-skills-smoke-${System.nanoTime()}")
        assertTrue(scratch.mkdirs())
        try {
            val isolatedContext = object : ContextWrapper(appContext) {
                override fun getFilesDir(): File = scratch.resolve("files").apply { mkdirs() }
            }
            // Settings are only passed as a dependency; this test never updates them.
            val manager = SkillManager(isolatedContext, getKoin().get<SettingsStore>())
            val catalog = appContext.assets.open("builtin_skills/catalog.json").bufferedReader().use {
                Json.parseToJsonElement(it.readText()).jsonObject
            }
            val imports = catalog.getValue("importedSkills").jsonArray +
                catalog["additionalLibraries"]?.jsonArray.orEmpty().flatMap {
                    it.jsonObject.getValue("skills").jsonArray
                }
            val skills = manager.listSkills()
            val expectedNames = imports.map { it.jsonObject.getValue("name").jsonPrimitive.content }.toSet() +
                "skill-creator"
            assertEquals(expectedNames.size, skills.size)
            assertEquals(expectedNames, skills.map { it.name }.toSet())
            assertTrue(skills.all { it.builtin })

            imports.forEach { entry ->
                val skill = entry.jsonObject
                val name = skill.getValue("name").jsonPrimitive.content
                skill.getValue("packagedFiles").jsonArray.forEach { asset ->
                    val path = asset.jsonPrimitive.content
                    val packaged = appContext.assets.open("builtin_skills/$name/$path").use { it.readBytes() }
                    val extracted = manager.resolveSkillFile(name, path)
                    assertTrue("Missing extracted asset $name/$path", extracted?.isFile == true)
                    assertArrayEquals("Asset changed during extraction: $name/$path", packaged, extracted!!.readBytes())
                }
            }

            val tools = createSkillTools(expectedNames, skills)
            val useSkill = tools.single { it.name == "use_skill" }
            expectedNames.forEach { name ->
                val parts = useSkill.execute(buildJsonObject { put("name", name) })
                val body = (parts.single() as UIMessagePart.Text).text
                assertFalse("Empty skill body: $name", body.isBlank())
                assertEquals(manager.readSkillBody(name), body)
            }
            val reference = imports.first { it.jsonObject.getValue("name").jsonPrimitive.content == "IDA-Skill" }
                .jsonObject.getValue("packagedFiles").jsonArray
                .map { it.jsonPrimitive.content }
                .first { it != "SKILL.md" && it.endsWith(".md") }
            val referenceParts = useSkill.execute(buildJsonObject {
                put("name", "IDA-Skill")
                put("path", reference)
            })
            assertEquals(
                manager.resolveSkillFile("IDA-Skill", reference)!!.readText(),
                (referenceParts.single() as UIMessagePart.Text).text,
            )

            // Verify the actual production dependency graph registers the built-in workspace mount.
            val productionManager = getKoin().get<SkillManager>()
            productionManager.ensureBuiltinSkillsExtracted()
            val workspaceManager = getKoin().get<WorkspaceManager>()
            val mount = workspaceManager.resolveRootfsPath(
                "unused-skill-smoke-root",
                "/builtin_skills/skill-creator/SKILL.md",
            )
            assertEquals(productionManager.getBuiltinSkillsDir().canonicalFile, mount.rootDir.canonicalFile)
            assertEquals("skill-creator/SKILL.md", mount.relativePath)
            assertTrue(mount.rootDir.resolve(mount.relativePath).isFile)
            assertTrue("Read-only smoke test wrote local skills", manager.getSkillsDir().listFiles().isNullOrEmpty())
        } finally {
            assertTrue("Failed to remove isolated smoke-test files", scratch.deleteRecursively())
        }
    }
}
