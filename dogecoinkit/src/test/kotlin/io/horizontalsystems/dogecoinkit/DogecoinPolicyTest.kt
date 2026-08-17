package io.horizontalsystems.dogecoinkit

import io.horizontalsystems.bitcoincore.DustCalculator
import io.horizontalsystems.bitcoincore.managers.SendValueErrors
import io.horizontalsystems.bitcoincore.managers.UnspentOutputQueue
import io.horizontalsystems.bitcoincore.models.Block
import io.horizontalsystems.bitcoincore.models.PublicKey
import io.horizontalsystems.bitcoincore.models.Transaction
import io.horizontalsystems.bitcoincore.models.TransactionOutput
import io.horizontalsystems.bitcoincore.storage.UnspentOutput
import io.horizontalsystems.bitcoincore.transactions.TransactionSizeCalculator
import io.horizontalsystems.bitcoincore.transactions.scripts.ScriptType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class DogecoinPolicyTest {
    private val sizeCalculator = TransactionSizeCalculator()
    private val dustCalculator = DustCalculator(
        dustRelayTxFee = 100_000,
        sizeCalculator = sizeCalculator,
        fixedDustThreshold = DogecoinKit.softDustLimit.toInt(),
    )

    @Test
    fun `normal recipients and change use one million koinu soft dust`() {
        assertEquals(1_000_000, dustCalculator.dust(ScriptType.P2PKH))
        assertEquals(1_000_000, dustCalculator.dust(ScriptType.P2SH))
        assertEquals(100_000, DogecoinKit.hardDustLimit)

        val belowDustChange = queue(value = 1_000_000).apply {
            push(utxo(1_500_000))
        }.calculate()
        assertNull(belowDustChange.changeValue)

        val retainedChange = queue(value = 1_000_000).apply {
            push(utxo(2_000_001))
        }.calculate()
        assertEquals(1_000_001L, retainedChange.changeValue)

        val exactSoftDustChange = queue(value = 1_000_000).apply {
            push(utxo(2_000_000))
        }.calculate()
        assertEquals(1_000_000L, exactSoftDustChange.changeValue)

        assertThrows(SendValueErrors.Dust::class.java) {
            queue(value = 999_999).apply { push(utxo(2_000_000)) }.calculate()
        }
    }

    @Test
    fun `clear covers every persisted sync mode`() {
        assertEquals(
            listOf(
                "Dogecoin-MainNet-wallet-Api-BIP44-Bip44",
                "Dogecoin-MainNet-wallet-Full-BIP44-Bip44",
                "Dogecoin-MainNet-wallet-Blockchair-BIP44-Bip44",
                "Dogecoin-MainNet-wallet-Api-BIP44-RecentPinned",
                "Dogecoin-MainNet-wallet-Full-BIP44-RecentPinned",
                "Dogecoin-MainNet-wallet-Blockchair-BIP44-RecentPinned",
            ),
            DogecoinKit.databaseNames(DogecoinKit.NetworkType.MainNet, "wallet")
        )
        assertEquals(DogecoinKit.FullSyncStart.Bip44, DogecoinKit.defaultFullSyncStart)
    }

    private fun queue(value: Long) = UnspentOutputQueue(
        UnspentOutputQueue.Parameters(
            value = value,
            senderPay = true,
            memo = null,
            fee = 0,
            outputsLimit = null,
            outputScriptType = ScriptType.P2PKH,
            changeType = ScriptType.P2PKH,
            pluginDataOutputSize = 0,
            changeToFirstInput = false,
        ),
        sizeCalculator,
        dustCalculator,
    )

    private fun utxo(value: Long) = UnspentOutput(
        TransactionOutput(value, 0, byteArrayOf(), ScriptType.P2PKH),
        PublicKey(),
        Transaction(),
        Block(),
    )
}
