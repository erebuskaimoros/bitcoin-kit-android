package io.horizontalsystems.dogecoinkit

import io.horizontalsystems.bitcoincore.BitcoinCore
import io.horizontalsystems.bitcoincore.blocks.BlockSyncer
import io.horizontalsystems.bitcoincore.blocks.Blockchain
import io.horizontalsystems.bitcoincore.core.IPublicKeyManager
import io.horizontalsystems.bitcoincore.models.Block
import io.horizontalsystems.bitcoincore.storage.Storage
import io.horizontalsystems.bitcoincore.transactions.BlockTransactionProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.`when`

class CheckpointRotationTest {
    @Test
    fun `older recent database resumes from stored tip without unseen new anchor`() {
        val network = MainNetDogecoin()
        val storage = mock(Storage::class.java)
        val oldStoredTip = Block().apply {
            height = 5_000_000
            headerHash = ByteArray(32) { 0x44 }
        }
        val staleTipAboveNewCheckpoint = Block().apply {
            height = 5_100_000
            headerHash = ByteArray(32) { 0x45 }
            stale = true
        }
        `when`(storage.lastBlock()).thenReturn(staleTipAboveNewCheckpoint)
        `when`(storage.getBlock(stale = false, sortedHeight = "DESC")).thenReturn(oldStoredTip)

        val resolved = DogecoinKit.resolveCheckpoint(
            BitcoinCore.SyncMode.Full(),
            network,
            storage,
            DogecoinKit.FullSyncStart.RecentPinned,
        )

        assertEquals(145_000, resolved.block.height)
        assertTrue(mockingDetails(storage).invocations.none { it.method.name == "saveBlock" })

        `when`(
            storage.getBlocks(
                heightGreaterThan = resolved.block.height,
                sortedBy = "height",
                limit = 10,
            )
        ).thenReturn(listOf(oldStoredTip))
        val blockSyncer = BlockSyncer(
            storage,
            mock(Blockchain::class.java),
            mock(BlockTransactionProcessor::class.java),
            mock(IPublicKeyManager::class.java),
            resolved,
        )

        val locator = blockSyncer.getBlockLocatorHashes(peerLastBlockHeight = 5_100_000)

        assertTrue(locator.any { it.contentEquals(oldStoredTip.headerHash) })
        assertFalse(locator.any { it.contentEquals(network.lastCheckpoint.block.headerHash) })
    }
}
