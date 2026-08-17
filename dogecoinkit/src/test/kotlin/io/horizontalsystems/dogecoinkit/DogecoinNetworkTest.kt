package io.horizontalsystems.dogecoinkit

import io.horizontalsystems.bitcoincore.extensions.hexToByteArray
import io.horizontalsystems.bitcoincore.extensions.toReversedHex
import io.horizontalsystems.bitcoincore.blocks.MerkleBlockExtractor
import io.horizontalsystems.bitcoincore.blocks.InvalidMerkleBlockException
import io.horizontalsystems.bitcoincore.network.messages.MerkleBlockMessage
import io.horizontalsystems.bitcoincore.storage.BlockHeader
import io.horizontalsystems.bitcoincore.utils.HashUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class DogecoinNetworkTest {

    @Test
    fun `mainnet parameters match Dogecoin Core v1 14 9`() {
        val network = MainNetDogecoin()

        assertEquals(22556, network.port)
        assertEquals(0xc0c0c0c0L, network.magic)
        assertEquals(30, network.addressVersion)
        assertEquals(22, network.addressScriptVersion)
        assertEquals(3, network.coinType)
        assertEquals(1_000_000, network.maxBlockSize)
        assertEquals(4_000_000, network.maxMessagePayloadSize)
        assertEquals("dogecoin", network.blockchairChainId)
        assertTrue(network.dnsSeeds.contains("seed.multidoge.org"))
        assertTrue(network.dnsSeeds.contains("seed2.multidoge.org"))
    }

    @Test
    fun `testnet parameters match Dogecoin Core v1 14 9`() {
        val network = TestNetDogecoin()

        assertEquals(44556, network.port)
        assertEquals(0xdcb7c1fcL, network.magic)
        assertEquals(113, network.addressVersion)
        assertEquals(196, network.addressScriptVersion)
        assertEquals(1, network.coinType)
        assertEquals(1_000_000, network.maxBlockSize)
        assertEquals(4_000_000, network.maxMessagePayloadSize)
        assertFalse(network.syncableFromApi)
    }

    @Test
    fun `Dogecoin merkle extractor uses Core base-size transaction count bound`() {
        val merkleRoot = ByteArray(32) { 0x42 }
        val header = BlockHeader(
            version = 1,
            previousBlockHeaderHash = ByteArray(32),
            merkleRoot = merkleRoot,
            timestamp = 0,
            bits = DogecoinConsensus.maxTargetBits,
            nonce = 0,
            hash = ByteArray(32),
        )
        fun message(txCount: Int) = MerkleBlockMessage(
            header = header,
            txCount = txCount,
            hashCount = 1,
            hashes = listOf(merkleRoot),
            flagsCount = 1,
            flags = byteArrayOf(0),
        )

        val extractor = MerkleBlockExtractor(MainNetDogecoin().maxBlockSize)
        val extracted = extractor.extract(message(16_666))

        assertTrue(extracted.associatedTransactionHashes.isEmpty())
        assertThrows(InvalidMerkleBlockException::class.java) {
            extractor.extract(message(16_667))
        }
    }

    @Test
    fun `genesis base header hashes to canonical mainnet block id`() {
        val header = "01000000" +
            "00".repeat(32) +
            "696ad20e2dd4365c7459b4a4a5af743d5e92c6da3229e6532cd605f6533f2a5b" +
            "24a6a152" +
            "f0ff0f1e" +
            "67860100"

        val hash = HashUtils.doubleSha256(header.hexToByteArray())
        assertEquals(
            "1a91e3dace36e2be3bf030a65679fe821aa1d6ef92e7c9902eb318182c355691",
            hash.toReversedHex()
        )
    }

    @Test
    fun `Dogecoin address versions accept Trust Wallet Core vectors`() {
        assertTrue(DogecoinKit.validateAddress("DLSSSUS3ex7YNDACJDxMER1ZMW579Vy8Zy"))
        assertTrue(DogecoinKit.validateAddress("AETZJzedcmLM2rxCM6VqCGF3YEMUjA3jMw"))
        assertFalse(DogecoinKit.validateAddress("1BoatSLRHtKNngkdXEeobR76b53LETtpyT"))
    }
}
