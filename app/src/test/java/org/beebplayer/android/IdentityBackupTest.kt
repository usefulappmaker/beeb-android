package org.beebplayer.android

import org.junit.Assert.*
import org.junit.Test

class IdentityBackupTest {
    @Test fun portableBackupIsAuthenticatedAndDoesNotDependOnDeviceKey() {
        val original = "synthetic identity package, not a credential".toByteArray()
        val password = "a long synthetic test password".toCharArray()
        val backup = IdentityBackup.encrypt(original, password)
        assertFalse(String(backup, Charsets.ISO_8859_1).contains("synthetic identity package"))
        assertArrayEquals(original, IdentityBackup.decrypt(backup, password))
        assertFalse(backup.contentEquals(IdentityBackup.encrypt(original, password)))
        assertTrue(runCatching { IdentityBackup.decrypt(backup, "incorrect test password".toCharArray()) }.isFailure)
        backup[backup.lastIndex] = (backup.last().toInt() xor 1).toByte()
        assertTrue(runCatching { IdentityBackup.decrypt(backup, password) }.isFailure)
    }
    @Test fun invalidBackupAndShortPasswordAreRejected() {
        assertTrue(runCatching { IdentityBackup.decrypt(ByteArray(100), "a long test password".toCharArray()) }.isFailure)
        assertTrue(runCatching { IdentityBackup.encrypt(byteArrayOf(1), "short".toCharArray()) }.isFailure)
    }
}
