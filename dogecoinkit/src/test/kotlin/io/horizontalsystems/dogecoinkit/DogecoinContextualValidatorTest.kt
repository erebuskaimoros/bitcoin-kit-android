package io.horizontalsystems.dogecoinkit

import io.horizontalsystems.bitcoincore.core.IStorage
import io.horizontalsystems.bitcoincore.managers.BlockValidatorHelper
import io.horizontalsystems.bitcoincore.models.Block
import io.horizontalsystems.bitcoincore.extensions.toHexString
import io.horizontalsystems.dogecoinkit.validators.DogecoinConsensusException
import io.horizontalsystems.dogecoinkit.validators.DogecoinContextualValidator
import io.horizontalsystems.dogecoinkit.validators.DogecoinTimeSource
import io.horizontalsystems.dogecoinkit.validators.DogecoinVersionActivations
import org.junit.Assert.assertThrows
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class DogecoinContextualValidatorTest {

    @Test
    fun `timestamp must be above median of the previous eleven blocks`() {
        val history = history(100)
        val validator = validator(history, DogecoinVersionActivations.MainNet, now = 20_000)
        val previous = history.getValue(100)

        assertThrows(DogecoinConsensusException::class.java) {
            validator.validate(block(101, version = 4, timestamp = 950), previous)
        }
        validator.validate(block(101, version = 4, timestamp = 951), previous)
    }

    @Test
    fun `timestamp may equal but not exceed current time plus two hours`() {
        val history = history(100)
        val validator = validator(history, DogecoinVersionActivations.MainNet, now = 1_000)
        val previous = history.getValue(100)

        validator.validate(block(101, version = 4, timestamp = 8_200), previous)
        assertThrows(DogecoinConsensusException::class.java) {
            validator.validate(block(101, version = 4, timestamp = 8_201), previous)
        }
    }

    @Test
    fun `mainnet enforces Core base version boundaries`() {
        validateVersionBoundary(
            DogecoinVersionActivations.MainNet,
            height = 1_034_383,
            rejectedBaseVersion = 2,
            acceptedBaseVersion = 3,
        )
        validateVersionBoundary(
            DogecoinVersionActivations.MainNet,
            height = 3_464_751,
            rejectedBaseVersion = 3,
            acceptedBaseVersion = 4,
        )
    }

    @Test
    fun `testnet enforces Core base version boundaries`() {
        validateVersionBoundary(
            DogecoinVersionActivations.TestNet,
            height = 708_658,
            rejectedBaseVersion = 2,
            acceptedBaseVersion = 3,
        )
        validateVersionBoundary(
            DogecoinVersionActivations.TestNet,
            height = 1_854_705,
            rejectedBaseVersion = 3,
            acceptedBaseVersion = 4,
        )
    }

    private fun validateVersionBoundary(
        activations: DogecoinVersionActivations,
        height: Int,
        rejectedBaseVersion: Int,
        acceptedBaseVersion: Int,
    ) {
        val history = history(height - 1)
        val validator = validator(history, activations, now = 20_000)
        val previous = history.getValue(height - 1)

        // Chain id and AuxPoW flag must not affect GetBaseVersion parity.
        val rejectedVersion = (DogecoinConsensus.chainId shl 16) or
            DogecoinConsensus.auxPowVersionFlag or rejectedBaseVersion
        val acceptedVersion = (DogecoinConsensus.chainId shl 16) or
            DogecoinConsensus.auxPowVersionFlag or acceptedBaseVersion
        assertThrows(DogecoinConsensusException::class.java) {
            validator.validate(block(height, rejectedVersion, 1_001), previous)
        }
        validator.validate(block(height, acceptedVersion, 1_001), previous)
    }

    private fun validator(
        history: Map<Int, Block>,
        activations: DogecoinVersionActivations,
        now: Long,
    ): DogecoinContextualValidator {
        val storage = mock(IStorage::class.java)
        history.values.forEach { stored ->
            `when`(storage.getBlock(stored.headerHash)).thenReturn(stored)
        }
        return DogecoinContextualValidator(
            BlockValidatorHelper(storage),
            activations,
            DogecoinTimeSource { now },
        )
    }

    private fun history(lastHeight: Int): Map<Int, Block> =
        (lastHeight - 10..lastHeight).associateWith { height ->
            // Median is 950, independent of the absolute height.
            block(height, version = 4, timestamp = 900L + (height - (lastHeight - 10)) * 10)
        }

    private fun block(height: Int, version: Int, timestamp: Long) = Block().apply {
        this.height = height
        this.version = version
        this.timestamp = timestamp
        headerHash = testHash(height)
        previousBlockHash = testHash(height - 1)
    }

    private fun testHash(height: Int) = ByteArray(32).also { bytes ->
        for (index in 0 until Int.SIZE_BYTES) {
            bytes[index] = (height ushr (index * 8)).toByte()
        }
    }
}
