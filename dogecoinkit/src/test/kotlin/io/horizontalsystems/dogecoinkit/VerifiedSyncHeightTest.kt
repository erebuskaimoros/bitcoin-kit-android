package io.horizontalsystems.dogecoinkit

import io.horizontalsystems.bitcoincore.models.NetworkAddress
import io.horizontalsystems.bitcoincore.network.messages.NetworkMessageParser
import io.horizontalsystems.bitcoincore.network.messages.NetworkMessageSerializer
import io.horizontalsystems.bitcoincore.network.messages.VersionMessage
import io.horizontalsystems.bitcoincore.network.peer.Peer
import io.horizontalsystems.bitcoincore.network.peer.VerifiedSyncHeightPolicy
import java.util.concurrent.ExecutorService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock

class VerifiedSyncHeightTest {
    @Test
    fun `peer below canonical checkpoint cannot be accepted for verified sync`() {
        val peer = peer()

        peer.onMessage(versionMessage(200_000))

        assertEquals(0, peer.announcedLastBlockHeight)
        assertFalse(policy.canFinish(announcedHeight = 200_000, downloadedHeight = 200_000))
    }

    @Test
    fun `peer at canonical checkpoint can be accepted and finish verified sync`() {
        val peer = peer()

        peer.onMessage(versionMessage(DogecoinConsensus.minimumVerifiedMainNetHeight))

        assertEquals(DogecoinConsensus.minimumVerifiedMainNetHeight, peer.announcedLastBlockHeight)
        assertTrue(
            policy.canFinish(
                announcedHeight = DogecoinConsensus.minimumVerifiedMainNetHeight,
                downloadedHeight = DogecoinConsensus.minimumVerifiedMainNetHeight,
            )
        )
    }

    @Test
    fun `testnet verified sync also requires Core last checkpoint`() {
        val testNetPolicy = VerifiedSyncHeightPolicy(DogecoinConsensus.minimumVerifiedTestNetHeight)

        assertFalse(testNetPolicy.canFinish(5_899_999, 5_899_999))
        assertTrue(testNetPolicy.canFinish(5_900_000, 5_900_000))
    }

    private val network = MainNetDogecoin()
    private val policy = VerifiedSyncHeightPolicy(DogecoinConsensus.minimumVerifiedMainNetHeight)

    private fun peer() = Peer(
        host = "127.0.0.1",
        network = network,
        listener = mock(Peer.Listener::class.java),
        networkMessageParser = mock(NetworkMessageParser::class.java),
        networkMessageSerializer = mock(NetworkMessageSerializer::class.java),
        executorService = mock(ExecutorService::class.java),
        minimumVerifiedBlockHeight = DogecoinConsensus.minimumVerifiedMainNetHeight,
    )

    private fun versionMessage(height: Int) = VersionMessage(
        network.protocolVersion,
        network.serviceFullNode or network.serviceBloomFilter,
        0,
        NetworkAddress().apply {
            address = ByteArray(16)
            port = network.port
        },
    ).apply { lastBlock = height }
}
