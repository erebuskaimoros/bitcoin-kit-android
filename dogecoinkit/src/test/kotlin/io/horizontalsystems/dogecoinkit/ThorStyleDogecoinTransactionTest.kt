package io.horizontalsystems.dogecoinkit

import io.horizontalsystems.bitcoincore.DustCalculator
import io.horizontalsystems.bitcoincore.core.IPublicKeyManager
import io.horizontalsystems.bitcoincore.core.IStorage
import io.horizontalsystems.bitcoincore.core.PluginManager
import io.horizontalsystems.bitcoincore.core.TransactionDataSorterFactory
import io.horizontalsystems.bitcoincore.core.Wallet
import io.horizontalsystems.bitcoincore.extensions.hexToByteArray
import io.horizontalsystems.bitcoincore.extensions.toHexString
import io.horizontalsystems.bitcoincore.extensions.toReversedHex
import io.horizontalsystems.bitcoincore.managers.BloomFilterManager
import io.horizontalsystems.bitcoincore.managers.IUnspentOutputSelector
import io.horizontalsystems.bitcoincore.models.Transaction
import io.horizontalsystems.bitcoincore.models.TransactionDataSortType
import io.horizontalsystems.bitcoincore.models.TransactionOutput
import io.horizontalsystems.bitcoincore.serializers.TransactionSerializer
import io.horizontalsystems.bitcoincore.storage.UnspentOutput
import io.horizontalsystems.bitcoincore.storage.UtxoFilters
import io.horizontalsystems.bitcoincore.transactions.PendingTransactionProcessor
import io.horizontalsystems.bitcoincore.transactions.TransactionCreator
import io.horizontalsystems.bitcoincore.transactions.TransactionSender
import io.horizontalsystems.bitcoincore.transactions.TransactionSizeCalculator
import io.horizontalsystems.bitcoincore.transactions.builder.EcdsaInputSigner
import io.horizontalsystems.bitcoincore.transactions.builder.InputSetter
import io.horizontalsystems.bitcoincore.transactions.builder.LockTimeSetter
import io.horizontalsystems.bitcoincore.transactions.builder.OutputSetter
import io.horizontalsystems.bitcoincore.transactions.builder.RecipientSetter
import io.horizontalsystems.bitcoincore.transactions.builder.SchnorrInputSigner
import io.horizontalsystems.bitcoincore.transactions.builder.TransactionBuilder
import io.horizontalsystems.bitcoincore.transactions.builder.TransactionSigner
import io.horizontalsystems.bitcoincore.transactions.scripts.ScriptType
import io.horizontalsystems.bitcoincore.utils.Base58AddressConverter
import io.horizontalsystems.hdwalletkit.HDWallet
import io.horizontalsystems.hdwalletkit.HDWallet.Purpose
import io.horizontalsystems.hdwalletkit.Mnemonic
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.mock

/** Release gate for the exact legacy transaction shape used by a THORChain DOGE deposit. */
class ThorStyleDogecoinTransactionTest {
    @Test
    fun `production builder signs inbound output THOR memo and first-input change`() {
        val seed = Mnemonic().toSeed(WORDS, "")
        val privateWallet = Wallet(HDWallet(seed, 3, Purpose.BIP44), 20)
        val inputPublicKey = privateWallet.publicKey(0, 0, true)
        val inputAddress = DogecoinKit.firstAddress(seed)
        val converter = Base58AddressConverter(30, 22)
        val sizeCalculator = TransactionSizeCalculator()
        val dustCalculator = DustCalculator(
            MainNetDogecoin().dustRelayTxFee,
            sizeCalculator,
            DogecoinKit.softDustLimit.toInt(),
        )
        val sorterFactory = TransactionDataSorterFactory()
        val pluginManager = PluginManager()
        val storage = mock(IStorage::class.java)

        val inputSetter = InputSetter(
            mock(IUnspentOutputSelector::class.java),
            mock(IPublicKeyManager::class.java),
            converter,
            ScriptType.P2PKH,
            sizeCalculator,
            pluginManager,
            dustCalculator,
            sorterFactory,
        )
        val builder = TransactionBuilder(
            RecipientSetter(converter, pluginManager),
            OutputSetter(sorterFactory),
            inputSetter,
            LockTimeSetter(storage),
        )
        val signer = TransactionSigner(
            EcdsaInputSigner(privateWallet, MainNetDogecoin()),
            SchnorrInputSigner(privateWallet),
        )
        val creator = TransactionCreator(
            builder,
            mock(PendingTransactionProcessor::class.java),
            mock(TransactionSender::class.java),
            signer,
            mock(BloomFilterManager::class.java),
        )

        val fundingHash = "22".repeat(32).hexToByteArray()
        val fundingOutput = TransactionOutput(
            value = INPUT_VALUE,
            index = 1,
            script = inputAddress.lockingScript,
            type = ScriptType.P2PKH,
            address = inputAddress.stringValue,
            lockingScriptPayload = inputAddress.lockingScriptPayload,
            publicKey = inputPublicKey,
        ).apply { transactionHash = fundingHash }
        val fundingTransaction = Transaction(version = 1, lockTime = 0).apply {
            hash = fundingHash
        }
        val unspentOutput = UnspentOutput(
            fundingOutput,
            inputPublicKey,
            fundingTransaction,
            null,
        )

        val transaction = creator.buildSignedTransaction(
            toAddress = INBOUND_ADDRESS,
            memo = THOR_MEMO,
            value = INBOUND_VALUE,
            feeRate = LIVE_ROUTE_FEE_RATE,
            senderPay = true,
            sortType = TransactionDataSortType.None,
            unspentOutputs = listOf(unspentOutput),
            pluginData = emptyMap(),
            rbfEnabled = false,
            changeToFirstInput = true,
            filters = UtxoFilters(),
        )

        assertEquals(1, transaction.inputs.size)
        assertArrayEquals(fundingHash, transaction.inputs.single().previousOutputTxHash)
        assertEquals(1L, transaction.inputs.single().previousOutputIndex)
        assertEquals(0xfffffffeL, transaction.inputs.single().sequence)

        assertEquals(3, transaction.outputs.size)
        assertEquals(INBOUND_VALUE, transaction.outputs[0].value)
        assertEquals(INBOUND_ADDRESS, transaction.outputs[0].address)
        assertEquals(ScriptType.P2PKH, transaction.outputs[0].scriptType)
        assertEquals(EXPECTED_CHANGE, transaction.outputs[1].value)
        assertArrayEquals(inputAddress.lockingScript, transaction.outputs[1].lockingScript)
        assertEquals(ScriptType.P2PKH, transaction.outputs[1].scriptType)
        assertEquals(0L, transaction.outputs[2].value)
        assertEquals(ScriptType.NULL_DATA, transaction.outputs[2].scriptType)
        assertEquals("6a1c" + THOR_MEMO.toByteArray().toHexString(), transaction.outputs[2].lockingScript.toHexString())

        val paidFee = INPUT_VALUE - transaction.outputs.sumOf(TransactionOutput::value)
        assertEquals(750_000, LIVE_ROUTE_FEE_RATE)
        assertEquals(EXPECTED_FEE, paidFee)
        assertEquals(DogecoinKit.softDustLimit.toInt(), dustCalculator.dust(ScriptType.P2PKH))

        val rawTransaction = TransactionSerializer.serialize(transaction).toHexString()
        assertEquals(EXPECTED_RAW_TRANSACTION, rawTransaction)
        assertEquals(EXPECTED_TRANSACTION_ID, transaction.header.hash.toReversedHex())
    }

    companion object {
        private val WORDS = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about".split(" ")
        private const val INBOUND_ADDRESS = "D9EGy2TqzyLmL1PJ9K8LhoM9HuZaogDkJY"
        private const val THOR_MEMO = "=:THOR.RUNE:thor1destination"
        private const val INPUT_VALUE = 50_000_000_000L
        private const val INBOUND_VALUE = 10_000_000_000L
        private const val LIVE_ROUTE_FEE_RATE = 750_000
        private const val EXPECTED_FEE = 198_750_000L
        private const val EXPECTED_CHANGE = 39_801_250_000L
        private const val EXPECTED_RAW_TRANSACTION =
            "02000000012222222222222222222222222222222222222222222222222222222222222222010000006a47304402204c665076b9594a5214ac486ea62ba32925379b06f1181df9b1ac27545000d19f02203f6df27e4344bcd6a8b70928cca431c4eac69352c90d3668f8fb7390f7316903012102cc6b0dc33aabcf3a23643e5e2919a80c50fb3dd2129ce409bbc5f0d4643d05e0feffffff0300e40b54020000001976a9142cdb49781141264ceb5fdb7fc5948441538eff4788acd0e05644090000001976a9144a483568665dcdfa68dd58a1f62893448a64333988ac00000000000000001e6a1c3d3a54484f522e52554e453a74686f723164657374696e6174696f6e00000000"
        private const val EXPECTED_TRANSACTION_ID =
            "f6d6c05a8e0311de5b4bbb5d37c1b00fbb8f2afccf4a138a30939e88726f126a"
    }
}
