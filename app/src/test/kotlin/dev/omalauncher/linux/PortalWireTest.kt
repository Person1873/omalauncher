package dev.omalauncher.linux

import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class PortalWireTest {
    @Test fun printingCannotFallThroughToTheUriActivity() {
        assertEquals(PortalWire.UiTarget.PRINT, PortalWire.uiTarget("print"))
        for (operation in listOf("open-uri", "open-file", "email"))
            assertEquals(PortalWire.UiTarget.OPEN, PortalWire.uiTarget(operation))
        for (operation in listOf("prepare-print", "inhibit", "notification", "unknown"))
            assertNull(PortalWire.uiTarget(operation))
    }

    private fun packet(vararg fields: Pair<String, String>) = fields.joinToString("\n") {
        it.first + "=" + Base64.getEncoder().encodeToString(it.second.toByteArray(Charsets.UTF_8))
    }.toByteArray(Charsets.UTF_8)

    @Test fun multilineBodyIsOneField() {
        val fields = PortalWire.decode(packet("version" to "1", "operation" to "email", "body" to "hello\noperation=bogus\nworld"))
        assertEquals("hello\noperation=bogus\nworld", fields["body"])
        assertEquals("email", fields["operation"])
    }
    @Test fun duplicateFieldsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            PortalWire.decode(packet("version" to "1", "operation" to "email", "operation" to "print"))
        }
    }
    @Test fun unknownVersionAndOperationRejected() {
        assertThrows(IllegalArgumentException::class.java) { PortalWire.decode(packet("version" to "2", "operation" to "email")) }
        assertThrows(IllegalArgumentException::class.java) { PortalWire.decode(packet("version" to "1", "operation" to "execute")) }
    }
    @Test fun oversizeAndInvalidEncodingRejected() {
        assertThrows(IllegalArgumentException::class.java) { PortalWire.decode(ByteArray(PortalWire.MAX_BYTES + 1)) }
        assertThrows(IllegalArgumentException::class.java) { PortalWire.decode("version=not!base64".toByteArray()) }
    }
    @Test fun emailLegacyAndListRecipientsAreAdditive() {
        assertArrayEquals(arrayOf("legacy@example.org", "first@example.org", "second@example.org"),
            PortalWire.emailRecipients(mapOf("address" to "legacy@example.org",
                "addresses-0" to "first@example.org", "addresses-1" to "second@example.org")))
    }
    @Test fun emailLegacyOnlyAndListOnlyRecipientsStayAvailable() {
        assertArrayEquals(arrayOf("legacy@example.org"), PortalWire.emailRecipients(mapOf("address" to "legacy@example.org")))
        assertArrayEquals(arrayOf("first@example.org"), PortalWire.emailRecipients(mapOf("addresses-0" to "first@example.org")))
        assertArrayEquals(emptyArray<String>(), PortalWire.emailRecipients(emptyMap()))
    }

    @Test fun emailListsAreNotSilentlyTruncatedAt64() {
        for (key in listOf("addresses", "cc", "bcc")) {
            val expected = (0 until 65).map { "$key-$it@example.org" }.toTypedArray()
            val fields = expected.mapIndexed { i, value -> "$key-$i" to value }.toMap()
            assertArrayEquals(expected, PortalWire.emailAddresses(fields, key))
        }
    }
    @Test fun openFileDirectoryAndShowContainingDirectoryAreDistinct() {
        assertEquals("/home/user/folder", PortalWire.directoryToOpen("/home/user/folder", containing = false))
        assertEquals("/home/user", PortalWire.directoryToOpen("/home/user/folder", containing = true))
        assertEquals("/", PortalWire.directoryToOpen("/", containing = false))
        assertEquals("/", PortalWire.directoryToOpen("/", containing = true))
        assertEquals("/", PortalWire.directoryToOpen("/document", containing = true))
    }

    @Test fun uriSchemesCannotLaunchArbitraryAndroidIntents() {
        assertTrue(PortalWire.safeUri("https://example.org/test"))
        assertTrue(PortalWire.safeUri("mailto:user@example.org"))
        assertFalse(PortalWire.safeUri("intent://settings#Intent;end"))
        assertFalse(PortalWire.safeUri("file:///data/private"))
        assertFalse(PortalWire.safeUri("javascript:alert(1)"))
        assertFalse(PortalWire.safeUri("content://private/provider"))
        assertFalse(PortalWire.safeUri("https://example.org\nintent:payload"))
    }
}
