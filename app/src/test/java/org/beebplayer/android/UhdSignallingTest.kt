package org.beebplayer.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UhdSignallingTest {
    private fun colr(primaries: Int, transfer: Int) =
        byteArrayOf(0, 0, 0, 16) + "colrnclx".toByteArray(Charsets.US_ASCII) +
            byteArrayOf((primaries shr 8).toByte(), primaries.toByte(), (transfer shr 8).toByte(), transfer.toByte())

    @Test fun hlgIsDetectedInAShortInitialisationSegment() {
        // A bounded initialisation fetch can end exactly on the colour box. The scan must
        // still reach a complete box that occupies the final twelve bytes.
        assertTrue(UhdSignalling.hlg(colr(9, 18)))
    }

    @Test fun hlgIsDetectedAtTheEndOfALongerSegment() {
        val prefix = ByteArray(200) { 0x11 }
        assertTrue(UhdSignalling.hlg(prefix + colr(9, 18)))
    }

    @Test fun nonHlgColourSignallingIsRejected() {
        assertFalse(UhdSignalling.hlg(colr(9, 16)))
        assertFalse(UhdSignalling.hlg(colr(1, 18)))
        assertFalse(UhdSignalling.hlg(byteArrayOf(0, 0, 0, 16) + "colrnclx".toByteArray()))
    }
}
