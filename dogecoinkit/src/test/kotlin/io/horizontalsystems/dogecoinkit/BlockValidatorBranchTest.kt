package io.horizontalsystems.dogecoinkit

import io.horizontalsystems.bitcoincore.core.IStorage
import io.horizontalsystems.bitcoincore.extensions.toHexString
import io.horizontalsystems.bitcoincore.managers.BlockValidatorHelper
import io.horizontalsystems.bitcoincore.models.Block
import org.junit.Assert.assertSame
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class BlockValidatorBranchTest {
    @Test
    fun `ancestor lookup follows candidate hashes across sibling branches`() {
        val common = block(100, 0x01)
        val active101 = block(101, 0x02, common)
        val active102 = block(102, 0x03, active101)
        val candidate101 = block(101, 0x12, common).apply { stale = true }
        val candidate102 = block(102, 0x13, candidate101).apply { stale = true }
        val candidate103 = block(103, 0x14, candidate102).apply { stale = true }
        val blocks = listOf(common, active101, active102, candidate101, candidate102)
            .associateBy { it.headerHash.toHexString() }
        val storage = mock(IStorage::class.java)
        blocks.values.forEach { stored ->
            `when`(storage.getBlock(stored.headerHash)).thenReturn(stored)
        }

        val helper = BlockValidatorHelper(storage)

        assertSame(candidate102, helper.getPrevious(candidate103, 1))
        assertSame(candidate101, helper.getPrevious(candidate103, 2))
        assertSame(common, helper.getPrevious(candidate103, 3))
    }

    private fun block(height: Int, marker: Int, parent: Block? = null) = Block().apply {
        this.height = height
        headerHash = ByteArray(32) { marker.toByte() }
        previousBlockHash = parent?.headerHash ?: ByteArray(32)
    }
}
