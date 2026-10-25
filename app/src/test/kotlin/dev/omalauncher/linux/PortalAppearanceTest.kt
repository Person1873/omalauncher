package dev.omalauncher.linux

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class PortalAppearanceTest {
    @get:Rule val dir = TemporaryFolder()

    @Test fun serializesSchemeAndSrgbWithoutAlpha() {
        assertEquals("dark\n#1234ab\n", PortalAppearance.text(false, 0xff1234ab.toInt()))
        assertEquals("light\n#00000a\n", PortalAppearance.text(true, 10))
    }

    @Test fun replacementNeverFollowsTheDestinationSymlink() {
        val runtime = dir.newFolder("runtime")
        val own = dir.newFile("private").apply { writeText("untouched") }
        val path = File(runtime, PortalAppearance.FILE_NAME)
        Files.createSymbolicLink(path.toPath(), own.toPath())
        PortalAppearance.write(runtime, true, 0x1234ab)
        assertEquals("untouched", own.readText())
        assertFalse(Files.isSymbolicLink(path.toPath()))
        assertEquals("light\n#1234ab\n", path.readText())
        assertEquals(listOf(PortalAppearance.FILE_NAME), runtime.list()!!.toList())
    }

    @Test fun repeatedUpdatesReplaceTheCompleteSnapshot() {
        val runtime = dir.newFolder("runtime")
        PortalAppearance.write(runtime, false, 0)
        PortalAppearance.write(runtime, true, 0xffffff)
        assertEquals("light\n#ffffff\n", File(runtime, PortalAppearance.FILE_NAME).readText())
        assertEquals(1, runtime.list()!!.size)
    }
}
