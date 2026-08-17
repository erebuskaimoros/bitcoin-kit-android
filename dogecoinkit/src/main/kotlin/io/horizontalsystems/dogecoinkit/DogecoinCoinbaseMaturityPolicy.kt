package io.horizontalsystems.dogecoinkit

import io.horizontalsystems.bitcoincore.managers.UtxoSpendabilityPolicy
import io.horizontalsystems.bitcoincore.storage.UnspentOutput

/** Coinbase spend depth from Dogecoin Core v1.14.9 CheckInputs. */
class DogecoinCoinbaseMaturityPolicy : UtxoSpendabilityPolicy {
    override fun isSpendable(utxo: UnspentOutput, lastBlockHeight: Int): Boolean {
        if (!utxo.transaction.isCoinbase) return true

        val originHeight = utxo.block?.height ?: return false
        val maturity = if (originHeight >= DogecoinConsensus.digiShieldHeight) {
            POST_DIGISHIELD_MATURITY
        } else {
            PRE_DIGISHIELD_MATURITY
        }
        val nextSpendHeight = lastBlockHeight + 1
        return nextSpendHeight - originHeight >= maturity
    }

    companion object {
        const val PRE_DIGISHIELD_MATURITY = 30
        const val POST_DIGISHIELD_MATURITY = 240
    }
}
