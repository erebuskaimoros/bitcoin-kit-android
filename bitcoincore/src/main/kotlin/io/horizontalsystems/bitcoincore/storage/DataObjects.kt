package io.horizontalsystems.bitcoincore.storage

import androidx.room.Embedded
import io.horizontalsystems.bitcoincore.core.IStorage
import io.horizontalsystems.bitcoincore.extensions.toHexString
import io.horizontalsystems.bitcoincore.models.Block
import io.horizontalsystems.bitcoincore.models.PublicKey
import io.horizontalsystems.bitcoincore.models.Transaction
import io.horizontalsystems.bitcoincore.models.TransactionInput
import io.horizontalsystems.bitcoincore.models.TransactionMetadata
import io.horizontalsystems.bitcoincore.models.TransactionOutput
import io.horizontalsystems.bitcoincore.serializers.TransactionSerializer
import io.horizontalsystems.bitcoincore.transactions.scripts.ScriptType
import io.horizontalsystems.bitcoincore.utils.HashUtils

class BlockHeader(
        val version: Int,
        val previousBlockHeaderHash: ByteArray,
        val merkleRoot: ByteArray,
        val timestamp: Long,
        val bits: Long,
        val nonce: Long,
        val hash: ByteArray,
        /**
         * The proof hash can differ from [hash] for merged-mined chains.  It is
         * deliberately in-memory metadata: a block is validated before it is
         * persisted, and persisted blocks are never treated as fresh network
         * proof without being parsed and validated again.
         */
        val proofOfWorkHash: ByteArray = hash,
        val auxPow: Boolean = false,
        val auxPowValidated: Boolean = false,
)

open class FullTransaction(
    val header: Transaction,
    val inputs: List<TransactionInput>,
    val outputs: List<TransactionOutput>,
    val forceHashUpdate: Boolean = true
) {

    lateinit var metadata: TransactionMetadata

    init {
        if (inputs.size == 1 &&
            inputs[0].previousOutputIndex == 0xffffffffL &&
            inputs[0].previousOutputTxHash.size == 32 &&
            inputs[0].previousOutputTxHash.all { it == 0.toByte() }
        ) {
            // Persist this on the transaction. TransactionInput's historical
            // primary key does not include the child transaction hash, so
            // coinbase inputs can collide and must not be the later source of
            // truth for maturity checks.
            header.isCoinbase = true
        }
        if (forceHashUpdate) {
            setHash(HashUtils.doubleSha256(TransactionSerializer.serialize(this, withWitness = false)))
        }
    }

    fun setHash(hash: ByteArray) {
        header.hash = hash

        metadata = TransactionMetadata(header.hash)

        inputs.forEach {
            it.transactionHash = header.hash
        }
        outputs.forEach {
            it.transactionHash = header.hash
        }
    }

}

class InputToSign(
        val input: TransactionInput,
        val previousOutput: TransactionOutput,
        val previousOutputPublicKey: PublicKey)

class TransactionWithBlock(
        @Embedded val transaction: Transaction,
        @Embedded val block: Block?)

class PublicKeyWithUsedState(
        @Embedded val publicKey: PublicKey,
        val usedCount: Int) {

    val used: Boolean
        get() = usedCount > 0
}

class InputWithPreviousOutput(
    val input: TransactionInput,
    val previousOutput: TransactionOutput?
)

class UnspentOutput(
    @Embedded val output: TransactionOutput,
    @Embedded val publicKey: PublicKey,
    @Embedded val transaction: Transaction,
    @Embedded val block: Block?
)

data class UtxoFilters(
    val scriptTypes: List<ScriptType>? = null,
    val maxOutputsCountForInputs: Int? = null
) {
    fun isEmpty() = scriptTypes == null && maxOutputsCountForInputs == null

    fun filterUtxo(utxo: UnspentOutput, storage: IStorage): Boolean {
        if (
            scriptTypes != null &&
            !scriptTypes.contains(utxo.output.scriptType)
        ) {
            return false
        }

        if (
            maxOutputsCountForInputs != null &&
            storage.getTransactionOutputsCount(utxo.transaction.hash) > maxOutputsCountForInputs
        ) {
            return false
        }


        return true
    }
}

class UnspentOutputInfo(
    val outputIndex: Int,
    val transactionHash: ByteArray,
    val timestamp: Long,
    val address: String?,
    val value: Long
) {
    companion object {
        fun fromUnspentOutput(unspentOutput: UnspentOutput): UnspentOutputInfo {
            return UnspentOutputInfo(
                unspentOutput.output.index,
                unspentOutput.output.transactionHash,
                unspentOutput.transaction.timestamp,
                unspentOutput.output.address,
                unspentOutput.output.value,
            )
        }
    }
}

class FullTransactionInfo(
        val block: Block?,
        val header: Transaction,
        val inputs: List<InputWithPreviousOutput>,
        val outputs: List<TransactionOutput>,
        val metadata: TransactionMetadata
) {

    val rawTransaction: String
        get() {
            return TransactionSerializer.serialize(fullTransaction).toHexString()
        }

    val fullTransaction: FullTransaction
        get() = FullTransaction(header, inputs.map { it.input }, outputs, forceHashUpdate = false).also {
            it.metadata = metadata
        }

}
