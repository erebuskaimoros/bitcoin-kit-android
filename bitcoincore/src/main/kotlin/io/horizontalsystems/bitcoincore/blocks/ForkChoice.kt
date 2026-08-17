package io.horizontalsystems.bitcoincore.blocks

import io.horizontalsystems.bitcoincore.crypto.CompactBits
import io.horizontalsystems.bitcoincore.models.Block
import java.math.BigInteger

interface ForkChoice {
    /** Returns true only when the candidate stale branch should replace active. */
    fun shouldSwitch(activeBranch: List<Block>, candidateBranch: List<Block>): Boolean
}

/** Preserves BitcoinCore's historical longest-tip behavior for existing kits. */
class LongestHeightForkChoice : ForkChoice {
    override fun shouldSwitch(activeBranch: List<Block>, candidateBranch: List<Block>): Boolean {
        val activeHeight = activeBranch.maxOfOrNull(Block::height) ?: Int.MIN_VALUE
        val candidateHeight = candidateBranch.maxOfOrNull(Block::height) ?: Int.MIN_VALUE
        return candidateHeight > activeHeight
    }
}

/**
 * Core-compatible cumulative block proof over the two post-fork branches.
 * Work before their common prefix cancels, so no schema migration is needed.
 */
class CumulativeWorkForkChoice(
    private val powLimit: BigInteger,
) : ForkChoice {
    init {
        require(powLimit.signum() > 0)
    }

    override fun shouldSwitch(activeBranch: List<Block>, candidateBranch: List<Block>): Boolean =
        cumulativeWork(candidateBranch) > cumulativeWork(activeBranch)

    private fun cumulativeWork(branch: List<Block>): BigInteger = branch.fold(BigInteger.ZERO) { total, block ->
        val target = CompactBits.decode(block.bits)
        require(target.signum() > 0 && target <= powLimit) {
            "invalid compact proof target at height ${block.height}"
        }
        total + TWO_TO_256.divide(target + BigInteger.ONE)
    }

    companion object {
        private val TWO_TO_256 = BigInteger.ONE.shiftLeft(256)
    }
}
