package io.horizontalsystems.dogecoinkit

import io.horizontalsystems.bitcoincore.extensions.hexToByteArray
import io.horizontalsystems.bitcoincore.io.BitcoinInputMarkable
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CompactSizeConsensusTest {
    @Test
    fun `fd encoding enforces its canonical threshold`() {
        assertThrows(IOException::class.java) { read("fdfc00") }
        assertThrows(IOException::class.java) { read("fd0100") }
        assertEquals(253, read("fdfd00"))
    }

    @Test
    fun `fe encoding is unsigned and enforces its canonical threshold`() {
        assertThrows(IOException::class.java) { read("feffff0000") }
        assertEquals(65_536, read("fe00000100"))
    }

    @Test
    fun `ff encoding enforces uint32 threshold and signed overflow`() {
        assertThrows(IOException::class.java) { read("ffffffff00000000", rangeCheck = false) }
        assertEquals(0x1_0000_0000L, read("ff0000000001000000", rangeCheck = false))
        assertThrows(IOException::class.java) { read("ffffffffffffffff", rangeCheck = false) }
    }

    @Test
    fun `Core MAX_SIZE is accepted at boundary and rejected above it`() {
        assertEquals(0x02000000, read("fe00000002"))
        assertThrows(IOException::class.java) { read("fe01000002") }
        assertThrows(IOException::class.java) { read("ff0000000001000000") }
    }

    private fun read(hex: String, rangeCheck: Boolean = true): Long =
        BitcoinInputMarkable(hex.hexToByteArray()).use { it.readVarInt(rangeCheck) }
}
