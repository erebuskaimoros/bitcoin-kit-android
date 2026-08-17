package io.horizontalsystems.bitcoincore.managers

import io.horizontalsystems.bitcoincore.storage.UnspentOutput

fun interface UtxoSpendabilityPolicy {
    fun isSpendable(utxo: UnspentOutput, lastBlockHeight: Int): Boolean
}

object AllowAllUtxoSpendabilityPolicy : UtxoSpendabilityPolicy {
    override fun isSpendable(utxo: UnspentOutput, lastBlockHeight: Int) = true
}
