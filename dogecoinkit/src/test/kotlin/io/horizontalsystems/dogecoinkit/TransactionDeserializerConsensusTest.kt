package io.horizontalsystems.dogecoinkit

import io.horizontalsystems.bitcoincore.extensions.hexToByteArray
import io.horizontalsystems.bitcoincore.extensions.toHexString
import io.horizontalsystems.bitcoincore.io.BitcoinInputMarkable
import io.horizontalsystems.bitcoincore.serializers.TransactionSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** Differential vectors for Dogecoin Core v1.14.9 UnserializeTransaction. */
class TransactionDeserializerConsensusTest {
    @Test
    fun `zero input marker followed by zero flag is an empty legacy transaction`() {
        val raw = "01000000000000000000"

        val transaction = deserialize(raw)

        assertFalse(transaction.header.segwit)
        assertEquals(0, transaction.inputs.size)
        assertEquals(0, transaction.outputs.size)
        assertEquals(0L, transaction.header.lockTime)
        assertEquals(raw, TransactionSerializer.serialize(transaction).toHexString())
    }

    @Test
    fun `witness flag bit one parses witness and round trips`() {
        val raw = extendedTransaction(flag = 1, witness = "0101aa")

        val transaction = deserialize(raw)

        assertTrue(transaction.header.segwit)
        assertEquals(1, transaction.inputs.size)
        assertEquals(1, transaction.outputs.size)
        assertEquals("aa", transaction.inputs.single().witness.single().toHexString())
        assertEquals(raw, TransactionSerializer.serialize(transaction).toHexString())
    }

    @Test
    fun `v1 14 9 accepts empty witness flag and canonicalizes without marker`() {
        val raw = extendedTransaction(flag = 1, witness = "00")

        val transaction = deserialize(raw)

        assertFalse(transaction.header.segwit)
        assertTrue(transaction.inputs.single().witness.isEmpty())
        assertEquals(
            "0100000001" + INPUT + OUTPUT + "00000000",
            TransactionSerializer.serialize(transaction).toHexString(),
        )
    }

    @Test
    fun `unknown transaction optional flag without witness bit is rejected`() {
        val raw = extendedTransaction(flag = 2, witness = "00")

        assertThrows(IllegalArgumentException::class.java) { deserialize(raw) }
    }

    @Test
    fun `unknown transaction optional flag alongside witness bit is rejected`() {
        val raw = extendedTransaction(flag = 3, witness = "00")

        assertThrows(IllegalArgumentException::class.java) { deserialize(raw) }
    }

    @Test
    fun `ordinary legacy transaction remains byte stable`() {
        val raw = "0100000001" + INPUT + OUTPUT + "00000000"

        val transaction = deserialize(raw)

        assertFalse(transaction.header.segwit)
        assertEquals(1, transaction.inputs.size)
        assertEquals(1, transaction.outputs.size)
        assertEquals(raw, TransactionSerializer.serialize(transaction).toHexString())
    }

    @Test
    fun `noncanonical transaction input count is rejected before parsing`() {
        val raw = "01000000fd0100" + INPUT + OUTPUT + "00000000"

        assertThrows(IOException::class.java) { deserialize(raw) }
    }

    private fun deserialize(raw: String) =
        BitcoinInputMarkable(raw.hexToByteArray()).use(TransactionSerializer::deserialize)

    private fun extendedTransaction(flag: Int, witness: String): String =
        "0100000000" + "%02x".format(flag) + "01" + INPUT + OUTPUT + witness + "00000000"

    companion object {
        private val INPUT = "00".repeat(32) + "00000000" + "00" + "ffffffff"
        private const val OUTPUT = "01" + "0100000000000000" + "00"
    }
}
