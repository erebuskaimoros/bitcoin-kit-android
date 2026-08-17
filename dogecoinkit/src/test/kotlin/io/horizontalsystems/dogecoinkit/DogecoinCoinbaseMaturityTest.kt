package io.horizontalsystems.dogecoinkit

import io.horizontalsystems.bitcoincore.core.IStorage
import io.horizontalsystems.bitcoincore.core.PluginManager
import io.horizontalsystems.bitcoincore.managers.UnspentOutputProvider
import io.horizontalsystems.bitcoincore.models.Block
import io.horizontalsystems.bitcoincore.models.PublicKey
import io.horizontalsystems.bitcoincore.models.Transaction
import io.horizontalsystems.bitcoincore.models.TransactionOutput
import io.horizontalsystems.bitcoincore.storage.UnspentOutput
import io.horizontalsystems.bitcoincore.storage.UtxoFilters
import io.horizontalsystems.bitcoincore.transactions.scripts.ScriptType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class DogecoinCoinbaseMaturityTest {
    @Test
    fun `pre-DigiShield coinbase becomes spendable at depth thirty`() {
        val fixture = fixture(originHeight = 144_999, coinbase = true)

        assertFalse(fixture.policy.isSpendable(fixture.utxo, lastBlockHeight = 145_027))
        assertTrue(fixture.policy.isSpendable(fixture.utxo, lastBlockHeight = 145_028))
    }

    @Test
    fun `DigiShield coinbase becomes spendable at depth two hundred forty`() {
        val fixture = fixture(originHeight = 145_000, coinbase = true)

        assertFalse(fixture.policy.isSpendable(fixture.utxo, lastBlockHeight = 145_238))
        assertTrue(fixture.policy.isSpendable(fixture.utxo, lastBlockHeight = 145_239))
    }

    @Test
    fun `normal payment is not delayed by coinbase maturity`() {
        val fixture = fixture(originHeight = 5_000_000, coinbase = false)

        assertTrue(fixture.policy.isSpendable(fixture.utxo, lastBlockHeight = 5_000_000))
    }

    @Test
    fun `provider excludes immature coinbase but includes one-confirmation payment`() {
        val storage = mock(IStorage::class.java)
        val coinbase = utxo(145_000, 0x11, isCoinbase = true)
        val payment = utxo(145_010, 0x22, isCoinbase = false)
        `when`(storage.getUnspentOutputs()).thenReturn(listOf(coinbase, payment))
        `when`(storage.lastBlock()).thenReturn(block(145_010))
        `when`(storage.getBlock(stale = false, sortedHeight = "DESC")).thenReturn(block(145_010))
        val policy = DogecoinCoinbaseMaturityPolicy()
        val provider = UnspentOutputProvider(storage, 1, PluginManager(), policy)

        val spendable = provider.getSpendableUtxo(UtxoFilters())
        val balance = provider.getBalance()

        assertEquals(listOf(payment.transaction.hash.toList()), spendable.map { it.transaction.hash.toList() })
        assertEquals(payment.output.value, balance.spendable)
        assertEquals(coinbase.output.value, balance.unspendableTimeLocked)
    }

    @Test
    fun `provider never matures coinbase against a taller stale fork`() {
        val storage = mock(IStorage::class.java)
        val coinbase = utxo(145_000, 0x44, isCoinbase = true)
        `when`(storage.getUnspentOutputs()).thenReturn(listOf(coinbase))
        `when`(storage.lastBlock()).thenReturn(block(145_239))
        `when`(storage.getBlock(stale = false, sortedHeight = "DESC")).thenReturn(block(145_238))
        val provider = UnspentOutputProvider(
            storage = storage,
            confirmationsThreshold = 1,
            pluginManager = PluginManager(),
            spendabilityPolicy = DogecoinCoinbaseMaturityPolicy(),
        )

        assertTrue(provider.getSpendableUtxo(UtxoFilters()).isEmpty())
        assertEquals(coinbase.output.value, provider.getBalance().unspendableTimeLocked)
    }

    private fun fixture(originHeight: Int, coinbase: Boolean): Fixture {
        val utxo = utxo(originHeight, 0x33, isCoinbase = coinbase)
        return Fixture(DogecoinCoinbaseMaturityPolicy(), utxo)
    }

    private fun utxo(height: Int, hashByte: Int, isCoinbase: Boolean): UnspentOutput {
        val hash = ByteArray(32) { hashByte.toByte() }
        val transaction = Transaction(version = 1).apply {
            this.hash = hash
            status = Transaction.Status.RELAYED
            this.isCoinbase = isCoinbase
        }
        val output = TransactionOutput(
            value = 100_000_000,
            index = 0,
            script = ByteArray(25),
            type = ScriptType.P2PKH,
        ).apply { transactionHash = hash }
        return UnspentOutput(output, PublicKey(), transaction, block(height))
    }

    private fun block(height: Int) = Block().apply { this.height = height }

    private data class Fixture(
        val policy: DogecoinCoinbaseMaturityPolicy,
        val utxo: UnspentOutput,
    )
}
