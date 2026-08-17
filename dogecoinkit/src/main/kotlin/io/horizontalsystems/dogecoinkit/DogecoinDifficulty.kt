package io.horizontalsystems.dogecoinkit

import io.horizontalsystems.bitcoincore.crypto.CompactBits
import java.math.BigInteger

/** Exact integer arithmetic from CalculateDogecoinNextWorkRequired in v1.14.9. */
object DogecoinDifficulty {
    fun calculateNextWorkRequired(
        previousHeight: Int,
        previousTimestamp: Long,
        previousBits: Long,
        firstBlockTimestamp: Long,
        digiShield: Boolean,
        targetTimespan: Long,
        powLimit: BigInteger,
    ): Long {
        val nextHeight = previousHeight + 1
        val actualTimespan = previousTimestamp - firstBlockTimestamp

        var modulatedTimespan = actualTimespan
        val minimumTimespan: Long
        val maximumTimespan: Long

        if (digiShield) {
            modulatedTimespan = targetTimespan + (modulatedTimespan - targetTimespan) / 8
            minimumTimespan = targetTimespan - targetTimespan / 4
            maximumTimespan = targetTimespan + targetTimespan / 2
        } else if (nextHeight > 10_000) {
            minimumTimespan = targetTimespan / 4
            maximumTimespan = targetTimespan * 4
        } else if (nextHeight > 5_000) {
            minimumTimespan = targetTimespan / 8
            maximumTimespan = targetTimespan * 4
        } else {
            minimumTimespan = targetTimespan / 16
            maximumTimespan = targetTimespan * 4
        }

        modulatedTimespan = modulatedTimespan.coerceIn(minimumTimespan, maximumTimespan)

        var target = CompactBits.decode(previousBits)
            .multiply(BigInteger.valueOf(modulatedTimespan))
            .divide(BigInteger.valueOf(targetTimespan))
        if (target > powLimit) target = powLimit

        return CompactBits.encode(target)
    }
}
