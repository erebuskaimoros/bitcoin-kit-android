package io.horizontalsystems.dogecoinkit.validators

import io.horizontalsystems.bitcoincore.blocks.validators.BlockValidatorException
import io.horizontalsystems.bitcoincore.blocks.validators.IBlockChainedValidator
import io.horizontalsystems.bitcoincore.crypto.CompactBits
import io.horizontalsystems.bitcoincore.managers.BlockValidatorHelper
import io.horizontalsystems.bitcoincore.models.Block
import io.horizontalsystems.dogecoinkit.DogecoinConsensus
import io.horizontalsystems.dogecoinkit.DogecoinDifficulty
import java.math.BigInteger

fun interface DogecoinTimeSource {
    fun currentTimeSeconds(): Long
}

data class DogecoinVersionActivations(
    val bip66Height: Int,
    val bip65Height: Int,
) {
    companion object {
        val MainNet = DogecoinVersionActivations(
            bip66Height = 1_034_383,
            bip65Height = 3_464_751,
        )
        val TestNet = DogecoinVersionActivations(
            bip66Height = 708_658,
            bip65Height = 1_854_705,
        )
    }
}

class DogecoinConsensusException(message: String) : BlockValidatorException(message)

class DogecoinProofOfWorkValidator : IBlockChainedValidator {
    override fun isBlockValidatable(block: Block, previousBlock: Block) = true

    override fun validate(block: Block, previousBlock: Block) {
        if (DogecoinConsensus.isAuxPow(block.version) && !block.auxPowValidated) {
            throw DogecoinConsensusException("AuxPoW was not structurally validated")
        }
        if (block.proofOfWorkHash.size != 32) {
            throw BlockValidatorException.InvalidProofOfWork()
        }
        val target = CompactBits.decode(block.bits)
        if (target.signum() <= 0 || target > DogecoinConsensus.powLimit ||
            BigInteger(1, block.proofOfWorkHash) > target
        ) {
            throw BlockValidatorException.InvalidProofOfWork()
        }
    }
}

class DogecoinActivationValidator(
    private val auxPowHeight: Int,
) : IBlockChainedValidator {
    override fun isBlockValidatable(block: Block, previousBlock: Block) = true

    override fun validate(block: Block, previousBlock: Block) {
        if (block.height < auxPowHeight && block.auxPow) {
            throw DogecoinConsensusException("AuxPoW block before activation at $auxPowHeight")
        }
        if (block.height >= auxPowHeight && DogecoinConsensus.isLegacy(block.version)) {
            throw DogecoinConsensusException("legacy block after AuxPoW activation")
        }
    }
}

class DogecoinCheckpointValidator(
    private val checkpoints: Map<Int, ByteArray>,
) : IBlockChainedValidator {
    override fun isBlockValidatable(block: Block, previousBlock: Block) = true

    override fun validate(block: Block, previousBlock: Block) {
        val expectedHash = checkpoints[block.height] ?: return
        if (!block.headerHash.contentEquals(expectedHash)) {
            throw DogecoinConsensusException("block does not match fixed checkpoint at ${block.height}")
        }
    }
}

/** Contextual header checks from ContextualCheckBlockHeader in v1.14.9. */
class DogecoinContextualValidator(
    private val helper: BlockValidatorHelper,
    private val versionActivations: DogecoinVersionActivations,
    private val timeSource: DogecoinTimeSource = DogecoinTimeSource {
        System.currentTimeMillis() / 1_000
    },
) : IBlockChainedValidator {
    override fun isBlockValidatable(block: Block, previousBlock: Block) = true

    override fun validate(block: Block, previousBlock: Block) {
        val baseVersion = DogecoinConsensus.baseVersion(block.version)
        if (
            block.height >= versionActivations.bip66Height && baseVersion < 3 ||
            block.height >= versionActivations.bip65Height && baseVersion < 4
        ) {
            throw DogecoinConsensusException("obsolete Dogecoin base block version $baseVersion")
        }

        val medianTimePast = medianTimePast(block, previousBlock)
        if (block.timestamp <= medianTimePast) {
            throw DogecoinConsensusException("block timestamp is not above median time past")
        }
        if (block.timestamp > timeSource.currentTimeSeconds() + MAX_FUTURE_BLOCK_TIME) {
            throw DogecoinConsensusException("block timestamp is more than two hours in the future")
        }
    }

    private fun medianTimePast(block: Block, previousBlock: Block): Long {
        val count = minOf(MEDIAN_TIME_SPAN, previousBlock.height + 1)
        val timestamps = ArrayList<Long>(count)
        timestamps += previousBlock.timestamp
        for (stepBack in 2..count) {
            val ancestor = helper.getPrevious(block, stepBack)
                ?: throw DogecoinConsensusException("missing checkpoint history for median time past")
            timestamps += ancestor.timestamp
        }
        timestamps.sort()
        return timestamps[timestamps.size / 2]
    }

    companion object {
        private const val MEDIAN_TIME_SPAN = 11
        private const val MAX_FUTURE_BLOCK_TIME = 2 * 60 * 60L
    }
}

class DogecoinDifficultyValidator(
    private val helper: BlockValidatorHelper,
    private val testNet: Boolean,
) : IBlockChainedValidator {
    override fun isBlockValidatable(block: Block, previousBlock: Block) = true

    override fun validate(block: Block, previousBlock: Block) {
        val expected = expectedBits(block, previousBlock)
        if (block.bits != expected) {
            throw BlockValidatorException.NotDifficultyTransitionEqualBits()
        }
    }

    internal fun expectedBits(block: Block, previousBlock: Block): Long {
        val digiShieldParameters = block.height >= DogecoinConsensus.digiShieldHeight

        if (testNet && allowsDigiShieldMinimumDifficulty(block, previousBlock)) {
            return DogecoinConsensus.maxTargetBits
        }

        // Core selects consensus parameters by the candidate height, but its
        // hard-coded new-protocol switch by the previous height. At candidate
        // 145,000 those new parameters already make DifficultyAdjustmentInterval
        // equal to one, even though previous.height is only 144,999.
        val newDifficultyProtocol = previousBlock.height >= DogecoinConsensus.digiShieldHeight
        val parameterInterval = if (digiShieldParameters) {
            1
        } else {
            DogecoinConsensus.legacyDifficultyInterval
        }
        val interval = if (newDifficultyProtocol) 1 else parameterInterval
        if (block.height % interval != 0) {
            if (!testNet) return previousBlock.bits
            if (digiShieldParameters) return previousBlock.bits
            if (block.timestamp > previousBlock.timestamp + 2 * DogecoinConsensus.targetSpacing) {
                return DogecoinConsensus.maxTargetBits
            }
            return lastNonMinimumDifficulty(previousBlock)
        }

        var blocksToGoBack = interval - 1
        if (block.height != interval) blocksToGoBack = interval
        val first = helper.getPrevious(block, blocksToGoBack + 1)
            ?: throw BlockValidatorException.NoCheckpointBlock()

        return DogecoinDifficulty.calculateNextWorkRequired(
            previousHeight = previousBlock.height,
            previousTimestamp = previousBlock.timestamp,
            previousBits = previousBlock.bits,
            firstBlockTimestamp = first.timestamp,
            digiShield = digiShieldParameters,
            targetTimespan = if (digiShieldParameters) {
                DogecoinConsensus.targetSpacing
            } else {
                DogecoinConsensus.legacyTargetTimespan
            },
            powLimit = DogecoinConsensus.powLimit,
        )
    }

    private fun allowsDigiShieldMinimumDifficulty(block: Block, previousBlock: Block): Boolean =
        block.height >= DogecoinConsensus.testNetMinDifficultyHeight &&
            previousBlock.height >= DogecoinConsensus.testNetMinDifficultyHeight &&
            block.timestamp > previousBlock.timestamp + 2 * DogecoinConsensus.targetSpacing

    private fun lastNonMinimumDifficulty(previousBlock: Block): Long {
        var cursor = previousBlock
        while (
            cursor.height % DogecoinConsensus.legacyDifficultyInterval != 0 &&
            cursor.bits == DogecoinConsensus.maxTargetBits
        ) {
            cursor = helper.getPrevious(cursor, 1) ?: return DogecoinConsensus.maxTargetBits
        }
        return cursor.bits
    }
}
