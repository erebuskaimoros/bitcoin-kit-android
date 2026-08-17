package io.horizontalsystems.dogecoinkit

import io.horizontalsystems.bitcoincore.core.IStorage
import io.horizontalsystems.bitcoincore.managers.BlockValidatorHelper
import io.horizontalsystems.bitcoincore.models.Block
import io.horizontalsystems.bitcoincore.models.Checkpoint
import io.horizontalsystems.bitcoincore.io.BitcoinOutput
import io.horizontalsystems.bitcoincore.utils.HashUtils
import io.horizontalsystems.bitcoincore.extensions.toReversedHex
import io.horizontalsystems.bitcoincore.extensions.toHexString
import io.horizontalsystems.dogecoinkit.validators.DogecoinActivationValidator
import io.horizontalsystems.dogecoinkit.validators.DogecoinConsensusException
import io.horizontalsystems.dogecoinkit.validators.DogecoinContextualValidator
import io.horizontalsystems.dogecoinkit.validators.DogecoinCheckpointValidator
import io.horizontalsystems.dogecoinkit.validators.DogecoinDifficultyValidator
import io.horizontalsystems.dogecoinkit.validators.DogecoinTimeSource
import io.horizontalsystems.dogecoinkit.validators.DogecoinVersionActivations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class DogecoinConsensusValidatorTest {

    @Test
    fun `all Core checkpoints at and after Dogecoin activation are enforced`() {
        assertCheckpoints(DogecoinConsensus.mainNetCheckpoints, MAINNET_CHECKPOINTS)
        assertCheckpoints(DogecoinConsensus.testNetCheckpoints, TESTNET_CHECKPOINTS)

        assertThrows(DogecoinConsensusException::class.java) {
            DogecoinCheckpointValidator(DogecoinConsensus.mainNetCheckpoints).validate(
                block(DogecoinConsensus.minimumVerifiedMainNetHeight).apply {
                    headerHash = ByteArray(32) { 0x55 }
                },
                block(DogecoinConsensus.minimumVerifiedMainNetHeight - 1),
            )
        }
    }

    private fun assertCheckpoints(checkpoints: Map<Int, ByteArray>, fixture: String) {
        val expected = fixture.trimIndent().lineSequence()
            .filter(String::isNotBlank)
            .associate { line ->
                val (height, displayHash) = line.split(' ')
                height.toInt() to displayHash
            }
        assertEquals(expected.keys, checkpoints.keys)
        expected.forEach { (height, displayHash) ->
            assertEquals(displayHash, checkpoints.getValue(height).toReversedHex())
        }

        val validator = DogecoinCheckpointValidator(checkpoints)
        checkpoints.forEach { (height, hash) ->
            validator.validate(
                block(height).apply { headerHash = hash },
                block(height - 1),
            )
        }
    }

    @Test
    fun `mainnet activation rejects early AuxPoW and late legacy`() {
        val validator = DogecoinActivationValidator(DogecoinConsensus.mainNetAuxPowHeight)

        val early = block(371_336, version = 0x00620104, auxPow = true)
        assertThrows(DogecoinConsensusException::class.java) {
            validator.validate(early, block(371_335))
        }

        val lateLegacy = block(371_337, version = 1)
        assertThrows(DogecoinConsensusException::class.java) {
            validator.validate(lateLegacy, block(371_336))
        }

        validator.validate(
            block(371_337, version = 0x00620104, auxPow = true),
            block(371_336),
        )
    }

    @Test
    fun `testnet block 145000 cannot use old timeout minimum difficulty`() {
        val prior = block(
            height = 144_998,
            bits = 0x1b123456,
            timestamp = 900,
        )
        val previous = block(
            height = 144_999,
            bits = DogecoinConsensus.maxTargetBits,
            timestamp = 1_000,
        )
        val candidate = block(
            height = 145_000,
            bits = previous.bits,
            timestamp = 2_000,
        )
        val validator = difficultyValidator(
            mapOf(prior.height to prior, previous.height to previous),
            testNet = true,
        )

        assertEquals(previous.bits, validator.expectedBits(candidate, previous))
        validator.validate(candidate, previous)
    }

    @Test
    fun `candidate 145000 uses new one-block interval and DigiShield parameters`() {
        val prior = block(
            height = 144_998,
            bits = 0x1b123456,
            timestamp = 900,
        )
        val previous = block(
            height = 144_999,
            bits = 0x1b123456,
            timestamp = 1_000,
        )
        val candidate = block(
            height = 145_000,
            bits = 0x1b13b8b2,
            timestamp = 1_050,
        )

        listOf(false, true).forEach { testNet ->
            val validator = difficultyValidator(
                mapOf(prior.height to prior, previous.height to previous),
                testNet,
            )
            assertEquals(0x1b13b8b2, validator.expectedBits(candidate, previous))
            validator.validate(candidate, previous)
        }
    }

    @Test
    fun `first post-checkpoint block has adjacent history for DigiShield`() {
        val checkpoint = Checkpoint("MainNetDogecoin.checkpoint")
        assertEquals(5_050_000, checkpoint.block.height)
        assertCheckpointHistory(
            checkpoint,
            expectedHash = "e7d4577405223918491477db725a393bcfc349d8ee63b0a4fde23cbfbfd81dea",
        )

        val byHeight = (listOf(checkpoint.block) + checkpoint.additionalBlocks).associateBy { it.height }
        val validator = difficultyValidator(byHeight, testNet = false)
        val candidate = block(
            height = 5_050_001,
            version = 0x00620104,
            bits = 0x1a019541,
            timestamp = 1_705_383_375,
        ).apply { previousBlockHash = checkpoint.block.headerHash }

        assertEquals(0x1a019541, validator.expectedBits(candidate, checkpoint.block))
        validator.validate(candidate, checkpoint.block)
        contextualValidator(byHeight).validate(candidate, checkpoint.block)
    }

    @Test
    fun `BIP44 checkpoint starts at DigiShield activation with full validation history`() {
        val checkpoint = Checkpoint("MainNetDogecoin-bip44.checkpoint")
        assertEquals(145_000, checkpoint.block.height)
        assertCheckpointHistory(
            checkpoint,
            expectedHash = "cc47cae70d7c5c92828d3214a266331dde59087d4a39071fa76ddfff9b7bde72",
        )

        val byHeight = (listOf(checkpoint.block) + checkpoint.additionalBlocks).associateBy { it.height }
        val candidate = block(
            height = 145_001,
            version = 2,
            bits = 0x1b671062,
            timestamp = 1_395_094_727,
        ).apply { previousBlockHash = checkpoint.block.headerHash }

        difficultyValidator(byHeight, testNet = false).validate(candidate, checkpoint.block)
        contextualValidator(byHeight).validate(candidate, checkpoint.block)
    }

    @Test
    fun `testnet all-block timeout starts only after height 157500`() {
        val previous = block(157_500, bits = 0x1b123456, timestamp = 1_000)
        val candidate = block(157_501, bits = DogecoinConsensus.maxTargetBits, timestamp = 1_121)
        val validator = difficultyValidator(mapOf(previous.height to previous), testNet = true)

        assertEquals(DogecoinConsensus.maxTargetBits, validator.expectedBits(candidate, previous))
    }

    private fun difficultyValidator(blocks: Map<Int, Block>, testNet: Boolean): DogecoinDifficultyValidator {
        val storage = mock(IStorage::class.java)
        blocks.values.forEach { stored ->
            `when`(storage.getBlock(stored.headerHash)).thenReturn(stored)
        }
        return DogecoinDifficultyValidator(BlockValidatorHelper(storage), testNet)
    }

    private fun contextualValidator(blocks: Map<Int, Block>): DogecoinContextualValidator {
        val storage = mock(IStorage::class.java)
        blocks.values.forEach { stored ->
            `when`(storage.getBlock(stored.headerHash)).thenReturn(stored)
        }
        return DogecoinContextualValidator(
            BlockValidatorHelper(storage),
            DogecoinVersionActivations.MainNet,
            DogecoinTimeSource { 2_000_000_000 },
        )
    }

    private fun assertCheckpointHistory(checkpoint: Checkpoint, expectedHash: String) {
        assertEquals(expectedHash, checkpoint.block.headerHash.toReversedHex())
        assertEquals(
            (checkpoint.block.height - 1 downTo checkpoint.block.height - 10).toList(),
            checkpoint.additionalBlocks.map { it.height },
        )

        val ordered = listOf(checkpoint.block) + checkpoint.additionalBlocks
        ordered.forEach { stored ->
            val serializedHeader = BitcoinOutput()
                .writeInt(stored.version)
                .write(stored.previousBlockHash)
                .write(stored.merkleRoot)
                .writeUnsignedInt(stored.timestamp)
                .writeUnsignedInt(stored.bits)
                .writeUnsignedInt(stored.nonce)
                .toByteArray()
            assertTrue(HashUtils.doubleSha256(serializedHeader).contentEquals(stored.headerHash))
        }
        ordered.zipWithNext().forEach { (newer, older) ->
            assertTrue(newer.previousBlockHash.contentEquals(older.headerHash))
        }
    }

    private fun block(
        height: Int,
        version: Int = 1,
        bits: Long = DogecoinConsensus.maxTargetBits,
        timestamp: Long = 0,
        auxPow: Boolean = false,
    ) = Block().apply {
        this.height = height
        this.version = version
        this.bits = bits
        this.timestamp = timestamp
        this.auxPow = auxPow
        this.auxPowValidated = auxPow
        this.proofOfWorkHash = ByteArray(32)
        this.headerHash = testHash(height)
        this.previousBlockHash = testHash(height - 1)
    }

    private fun testHash(height: Int) = ByteArray(32).also { bytes ->
        for (index in 0 until Int.SIZE_BYTES) {
            bytes[index] = (height ushr (index * 8)).toByte()
        }
    }

    companion object {
        private const val MAINNET_CHECKPOINTS = """
            145000 cc47cae70d7c5c92828d3214a266331dde59087d4a39071fa76ddfff9b7bde72
            371337 60323982f9c5ff1b5a954eac9dc1269352835f47c2c5222691d80f0d50dcf053
            450000 d279277f8f846a224d776450aa04da3cf978991a182c6f3075db4c48b173bbd7
            771275 1b7d789ed82cbdc640952e7e7a54966c6488a32eaad54fc39dff83f310dbaaed
            1000000 6aae55bea74235f0c80bd066349d4440c31f2d0f27d54265ecd484d8c1d11b47
            1250000 00c7a442055c1a990e11eea5371ca5c1c02a0677b33cc88ec728c45edc4ec060
            1500000 f1d32d6920de7b617d51e74bdf4e58adccaa582ffdc8657464454f16a952fca6
            1750000 5c8e7327984f0d6f59447d89d143e5f6eafc524c82ad95d176c5cec082ae2001
            2000000 9914f0e82e39bbf21950792e8816620d71b9965bdbbc14e72a95e3ab9618fea8
            2031142 893297d89afb7599a3c571ca31a3b80e8353f4cf39872400ad0f57d26c4c5d42
            2250000 0a87a8d4e40dca52763f93812a288741806380cd569537039ee927045c6bc338
            2510150 77e3f4a4bcb4a2c15e8015525e3d15b466f6c022f6ca82698f329edef7d9777e
            2750000 d4f8abb835930d3c4f92ca718aaa09bef545076bd872354e0b2b85deefacf2e3
            3000000 195a83b091fb3ee7ecb56f2e63d01709293f57f971ccf373d93890c8dc1033db
            3250000 7f3e28bf9e309c4b57a4b70aa64d3b2ea5250ae797af84976ddc420d49684034
            3500000 eaa303b93c1c64d2b3a2cdcf6ccf21b10cc36626965cc2619661e8e1879abdfb
            3606083 954c7c66dee51f0a3fb1edb26200b735f5275fe54d9505c76ebd2bcabac36f1e
            3854173 e4b4ecda4c022406c502a247c0525480268ce7abbbef632796e8ca1646425e75
            3963597 2b6927cfaa5e82353d45f02be8aadd3bfd165ece5ce24b9bfa4db20432befb5d
            4303965 ed7d266dcbd8bb8af80f9ccb8deb3e18f9cc3f6972912680feeb37b090f8cee0
            5050000 e7d4577405223918491477db725a393bcfc349d8ee63b0a4fde23cbfbfd81dea
        """

        private const val TESTNET_CHECKPOINTS = """
            483173 a804201ca0aceb7e937ef7a3c613a9b7589245b10cc095148c4ce4965b0b73b5
            591117 5f6b93b2c28cedf32467d900369b8be6700f0649388a7dbfd3ebd4a01b1ffad8
            658924 ed6c8324d9a77195ee080f225a0fca6346495e08ded99bcda47a8eea5a8a620b
            703635 839fa54617adcd582d53030a37455c14a87a806f6615aa8213f13e196230ff7f
            1000000 1fe4d44ea4d1edb031f52f0d7c635db8190dc871a190654c41d2450086b8ef0e
            1202214 a2179767a87ee4e95944703976fee63578ec04fa3ac2fc1c9c2c83587d096977
            1250000 b46affb421872ca8efa30366b09694e2f9bf077f7258213be14adb05a9f41883
            1500000 0caa041b47b4d18a4f44bdc05cef1a96d5196ce7b2e32ad3e4eb9ba505144917
            1750000 8042462366d854ad39b8b95ed2ca12e89a526ceee5a90042d55ebb24d5aab7e9
            2000000 d6acde73e1b42fc17f29dcc76f63946d378ae1bd4eafab44d801a25be784103c
            2250000 c4342ae6d9a522a02e5607411df1b00e9329563ef844a758d762d601d42c86dc
            2500000 3a66ec4933fbb348c9b1889aaf2f732fe429fd9a8f74fee6895eae061ac897e2
            2750000 473ea9f625d59f534ffcc9738ffc58f7b7b1e0e993078614f5484a9505885563
            3062910 113c41c00934f940a41f99d18b2ad9aefd183a4b7fe80527e1e6c12779bd0246
            3286675 07fef07a255d510297c9189dc96da5f4e41a8184bc979df8294487f07fee1cf3
            3445426 70574db7856bd685abe7b0a8a3e79b29882620645bd763b01459176bceb58cd1
            3976284 af23c3e750bb4f2ce091235f006e7e4e2af453d4c866282e7870471dcfeb4382
            5900000 199bea6a442310589cbb50a193a30b097c228bd5a0f21af21e4e53dd57c382d3
        """
    }
}
