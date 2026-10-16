package dev.omalauncher.linux

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files

class LinuxDocumentsProviderTest {
    @Test fun idsArePathsUnderHome() {
        val home = LinuxDocumentsProvider.HOME_ID
        assertEquals("home/Downloads", LinuxDocumentsProvider.childId(home, "Downloads"))
        assertEquals("home/Downloads/benchy.gcode", LinuxDocumentsProvider.childId("home/Downloads", "benchy.gcode"))
        assertEquals("home/Downloads", LinuxDocumentsProvider.parentOf("home/Downloads/benchy.gcode"))
        assertEquals(home, LinuxDocumentsProvider.parentOf("home/Downloads"))
        assertEquals("", LinuxDocumentsProvider.relativePath(home))
        assertEquals("Downloads/benchy.gcode", LinuxDocumentsProvider.relativePath("home/Downloads/benchy.gcode"))
    }

    @Test(expected = FileNotFoundException::class)
    fun idsOutsideHomeAreRefused() {
        LinuxDocumentsProvider.relativePath("etc/passwd")
    }

    @Test fun dotfilesShowButProotLinksDont() {
        assertEquals(false, LinuxDocumentsProvider.isProotLink(".config"))
        assertEquals(false, LinuxDocumentsProvider.isProotLink(".ssh"))
        assertEquals(true, LinuxDocumentsProvider.isProotLink(".l2s.known_hosts0001.0001"))
    }

    @Test fun pickerNamesStayOneSegment() {
        assertEquals("a_b", LinuxDocumentsProvider.safeName("a/b"))
        assertEquals("untitled", LinuxDocumentsProvider.safeName(".."))
        assertEquals("untitled", LinuxDocumentsProvider.safeName("  "))
        assertEquals("print.gcode", LinuxDocumentsProvider.safeName(" print.gcode "))
    }

    @Test fun takenNamesGetANumber() {
        val dir = Files.createTempDirectory("docs").toFile()
        try {
            assertEquals("part.stl", LinuxDocumentsProvider.freeName(dir, "part.stl"))
            File(dir, "part.stl").createNewFile()
            assertEquals("part (1).stl", LinuxDocumentsProvider.freeName(dir, "part.stl"))
            File(dir, "part (1).stl").createNewFile()
            assertEquals("part (2).stl", LinuxDocumentsProvider.freeName(dir, "part.stl"))
            File(dir, "notes").mkdir()
            assertEquals("notes (1)", LinuxDocumentsProvider.freeName(dir, "notes"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
