package io.horizontalsystems.dogecoinkit

import org.junit.Assert.assertEquals
import org.junit.Test

class DigiShieldDifficultyTest {

    @Test
    fun `calculation matches Dogecoin Core v1 14 9 vectors`() {
        val vectors = listOf(
            Vector(239, 1386475638, 0x1e0ffff0, 1386474927, false, 0x1e00ffff),
            Vector(9599, 1386954113, 0x1c1a1206, 1386942008, false, 0x1c15ea59),
            Vector(145000, 1395094679, 0x1b499dfd, 1395094427, true, 0x1b671062),
            Vector(145107, 1395101360, 0x1b3439cd, 1395100835, true, 0x1b4e56b3),
            Vector(149423, 1395380447, 0x1b446f21, 1395380517, true, 0x1b335358),
            Vector(145001, 1395094727, 0x1b671062, 1395094679, true, 0x1b6558a4),
        )

        vectors.forEach { vector ->
            assertEquals(
                vector.expected,
                DogecoinDifficulty.calculateNextWorkRequired(
                    previousHeight = vector.height,
                    previousTimestamp = vector.timestamp,
                    previousBits = vector.bits,
                    firstBlockTimestamp = vector.firstTimestamp,
                    digiShield = vector.digiShield,
                    targetTimespan = if (vector.digiShield) 60 else 14_400,
                    powLimit = DogecoinConsensus.powLimit,
                )
            )
        }
    }

    private data class Vector(
        val height: Int,
        val timestamp: Long,
        val bits: Long,
        val firstTimestamp: Long,
        val digiShield: Boolean,
        val expected: Long,
    )
}
