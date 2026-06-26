package com.pacvue.deploy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ExecutableResolverTest {
    @Test
    fun `resolves node from common macOS locations when it is missing from PATH`() {
        val nodePath = ExecutableResolver.resolveExecutableCommand(
            command = "node",
            pathValue = "/usr/bin:/bin",
            fileExists = { it == "/opt/homebrew/bin/node" },
        )

        assertEquals("/opt/homebrew/bin/node", nodePath)
    }

    @Test
    fun `extends child process PATH with common macOS executable locations`() {
        val pathValue = ExecutableResolver.extendExecutablePath("/usr/bin:/bin")

        assertEquals("/usr/bin:/bin:/opt/homebrew/bin:/usr/local/bin:/opt/local/bin", pathValue)
    }

    @Test
    fun `exposes common Windows executable locations`() {
        val directories = ExecutableResolver.windowsExecutableDirectories()

        assertTrue(directories.any { it.contains("Git\\cmd") })
        assertTrue(directories.any { it.contains("GitHub CLI") })
    }

    @Test
    fun `finds Windows git executable by exe suffix in common directories`() {
        val gitPath = ExecutableResolver.findSystemExecutable(
            command = "git",
            workingDir = java.io.File("."),
        )

        // On macOS CI/dev machines this stays null; on Windows it resolves git.exe.
        if (System.getProperty("os.name").lowercase().contains("windows")) {
            assertNotNull(gitPath)
            assertTrue(gitPath.endsWith("git.exe", ignoreCase = true))
        }
    }
}
