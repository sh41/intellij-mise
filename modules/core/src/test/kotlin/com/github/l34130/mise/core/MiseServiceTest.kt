package com.github.l34130.mise.core

import com.github.l34130.mise.core.model.MiseShellScriptTask
import com.github.l34130.mise.core.model.MiseTomlTableTask
import com.intellij.openapi.components.service
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.reflect.KClass

@Suppress("ktlint:standard:function-naming")
class MiseServiceTest : BasePlatformTestCase() {
    override fun getTestDataPath(): String = "src/test/testData/src/"

    fun `test tasks`() {
        // TODO: fix the test directory
        return
        myFixture.configureByFiles(*allTestFiles())

        val service = project.service<MiseTaskResolver>()

        val vf: VirtualFile = VirtualFileManager.getInstance().findFileByUrl("temp:///src") ?: error("Base directory not found")
        val tasks = runBlocking { service.getMiseTasks() }

        listOf<TestResult>(
            TestResult("default-inline-table-task", "/src/mise.toml", MiseTomlTableTask::class),
            TestResult("default-table-task", "/src/mise.toml", MiseTomlTableTask::class),
            TestResult("lint", "/src/mise.toml", MiseTomlTableTask::class),
            TestResult("lint:test1", "/src/xtasks/lint/test1", MiseShellScriptTask::class),
            TestResult("lint:test2", "/src/xtasks/lint/test2", MiseShellScriptTask::class),
            TestResult("xtask", "/src/xtasks/xtask.sh", MiseShellScriptTask::class),
            TestResult("task-in-test-config", "/src/mise.test.toml", MiseTomlTableTask::class),
        ).forEach { (name, source, type) ->
            val task = tasks.find { it.name == name }
            assertNotNull("Task '$name' not found", task)
            assertEquals("Task '$name' has wrong source", source, task!!.source)
            assertEquals("Task '$name' has wrong type", type, task::class)
        }
    }

    private data class TestResult(
        val name: String,
        val source: String,
        val type: KClass<*>,
    )

    private fun allTestFiles(): Array<String> =
        File(testDataPath)
            .walk()
            .filter { it.isFile }
            .map { it.relativeTo(File(testDataPath)).path.replace('\\', '/') }
            .toList()
            .toTypedArray()
}
