package io.horizontalsystems.dogecoinkit

import io.horizontalsystems.bitcoincore.blocks.CumulativeWorkForkChoice
import io.horizontalsystems.bitcoincore.blocks.Blockchain
import io.horizontalsystems.bitcoincore.blocks.IBlockchainDataListener
import io.horizontalsystems.bitcoincore.core.IStorage
import io.horizontalsystems.bitcoincore.models.Block
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class DogecoinForkChoiceTest {

    @Test
    fun `shorter branch with more cumulative proof wins`() {
        val active = (1..10).map { block(it, DogecoinConsensus.maxTargetBits) }
        val candidate = listOf(block(1, 0x1b0404cb))

        assertTrue(policy.shouldSwitch(active, candidate))
    }

    @Test
    fun `longer branch with less cumulative proof does not win`() {
        val active = listOf(block(1, 0x1b0404cb))
        val candidate = (1..10).map { block(it, DogecoinConsensus.maxTargetBits) }

        assertFalse(policy.shouldSwitch(active, candidate))
    }

    @Test
    fun `equal cumulative proof deterministically keeps active branch`() {
        val active = listOf(block(1, 0x1b0404cb))
        val candidate = listOf(block(1, 0x1b0404cb))

        assertFalse(policy.shouldSwitch(active, candidate))
    }

    @Test
    fun `invalid compact target cannot participate in fork choice`() {
        assertThrows(IllegalArgumentException::class.java) {
            policy.shouldSwitch(
                listOf(block(1, DogecoinConsensus.maxTargetBits)),
                listOf(block(1, 0)),
            )
        }
    }

    @Test
    fun `blockchain switches to shorter higher-work stored branch`() {
        val active = chain(1..10, DogecoinConsensus.maxTargetBits, 0x20)
        val candidate = chain(1..1, 0x1b0404cb, 0x30, stale = true)
        val storage = forkStorage(active, candidate)
        val blockchain = Blockchain(
            storage,
            null,
            mock(IBlockchainDataListener::class.java),
            policy,
        )

        blockchain.handleFork()

        verify(storage).deleteBlocks(active)
        verify(storage, never()).deleteBlocks(candidate)
        verify(storage).unstaleAllBlocks()
    }

    @Test
    fun `blockchain keeps shorter higher-work active branch`() {
        val active = chain(1..1, 0x1b0404cb, 0x20)
        val candidate = chain(1..10, DogecoinConsensus.maxTargetBits, 0x30, stale = true)
        val storage = forkStorage(active, candidate)
        val blockchain = Blockchain(
            storage,
            null,
            mock(IBlockchainDataListener::class.java),
            policy,
        )

        blockchain.handleFork()

        verify(storage).deleteBlocks(candidate)
        verify(storage, never()).deleteBlocks(active)
        verify(storage, never()).unstaleAllBlocks()
    }

    @Test
    fun `sibling work is never pooled into a nonexistent candidate chain`() {
        val active = chain(1..2, DogecoinConsensus.maxTargetBits, 0x20)
        val siblings = (0..2).map { index ->
            chain(1..1, DogecoinConsensus.maxTargetBits, 0x30 + index, stale = true).single()
        }
        val storage = forkStorage(active, siblings)
        val blockchain = Blockchain(storage, null, mock(IBlockchainDataListener::class.java), policy)

        blockchain.handleFork()

        verify(storage).deleteBlocks(siblings)
        verify(storage, never()).deleteBlocks(active)
        verify(storage, never()).unstaleAllBlocks()
        assertTrue(mockingDetails(storage).invocations.none { it.method.name == "updateBlock" })
    }

    @Test
    fun `only winning connected path is activated and sibling is deleted`() {
        val active = chain(1..2, DogecoinConsensus.maxTargetBits, 0x20)
        val losing = chain(1..1, DogecoinConsensus.maxTargetBits, 0x30, stale = true)
        val winning = chain(1..1, 0x1b0404cb, 0x40, stale = true)
        val storage = forkStorage(active, losing + winning)
        val blockchain = Blockchain(storage, null, mock(IBlockchainDataListener::class.java), policy)

        blockchain.handleFork()

        verify(storage).deleteBlocks(active)
        verify(storage).deleteBlocks(losing)
        verify(storage, never()).deleteBlocks(winning)
        verify(storage, never()).unstaleAllBlocks()
        verify(storage, times(1)).updateBlock(winning.single())
        assertFalse(winning.single().stale)
    }

    private val policy = CumulativeWorkForkChoice(DogecoinConsensus.powLimit)

    private fun forkStorage(active: List<Block>, candidate: List<Block>): IStorage {
        val storage = mock(IStorage::class.java)
        `when`(storage.getBlock(stale = true, sortedHeight = "ASC")).thenReturn(candidate.first())
        `when`(
            storage.getBlocks(
                heightGreaterOrEqualTo = candidate.minOf(Block::height),
                stale = false,
            )
        ).thenReturn(active)
        `when`(storage.getBlocks(stale = true)).thenReturn(candidate)
        val commonParent = block(0, DogecoinConsensus.maxTargetBits).apply {
            headerHash = commonParentHash
        }
        `when`(storage.getBlock(commonParentHash)).thenReturn(commonParent)
        (active + candidate).forEach { stored ->
            `when`(storage.getBlockTransactions(stored)).thenReturn(emptyList())
        }
        return storage
    }

    private fun block(height: Int, bits: Long) = Block().apply {
        this.height = height
        this.bits = bits
    }

    private fun chain(
        heights: IntRange,
        bits: Long,
        branchMarker: Int,
        stale: Boolean = false,
    ): List<Block> {
        var previousHash = commonParentHash
        return heights.map { height ->
            block(height, bits).apply {
                headerHash = ByteArray(32).also { bytes ->
                    bytes[0] = branchMarker.toByte()
                    bytes[1] = height.toByte()
                }
                previousBlockHash = previousHash
                this.stale = stale
                previousHash = headerHash
            }
        }
    }

    companion object {
        private val commonParentHash = ByteArray(32) { 0x01 }
    }
}
