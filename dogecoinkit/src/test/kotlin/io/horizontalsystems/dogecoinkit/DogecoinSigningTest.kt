package io.horizontalsystems.dogecoinkit

import io.horizontalsystems.bitcoincore.core.Wallet
import io.horizontalsystems.bitcoincore.extensions.hexToByteArray
import io.horizontalsystems.bitcoincore.extensions.toHexString
import io.horizontalsystems.bitcoincore.extensions.toReversedHex
import io.horizontalsystems.bitcoincore.models.Transaction
import io.horizontalsystems.bitcoincore.models.TransactionInput
import io.horizontalsystems.bitcoincore.models.TransactionOutput
import io.horizontalsystems.bitcoincore.serializers.TransactionSerializer
import io.horizontalsystems.bitcoincore.storage.FullTransaction
import io.horizontalsystems.bitcoincore.storage.InputToSign
import io.horizontalsystems.bitcoincore.transactions.builder.EcdsaInputSigner
import io.horizontalsystems.bitcoincore.transactions.scripts.OpCodes
import io.horizontalsystems.bitcoincore.transactions.scripts.ScriptType
import io.horizontalsystems.bitcoincore.utils.Base58AddressConverter
import io.horizontalsystems.hdwalletkit.HDWallet
import io.horizontalsystems.hdwalletkit.HDWallet.Purpose
import io.horizontalsystems.hdwalletkit.Mnemonic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DogecoinSigningTest {
    private val words = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about".split(" ")
    private val seed = Mnemonic().toSeed(words, "")

    @Test
    fun `BIP44 coin type 3 derives independent reference address`() {
        val address = DogecoinKit.firstAddress(seed)

        // Cross-checked with bip_utils' Dogecoin BIP44 implementation at
        // m/44'/3'/0'/0/0; Trust Wallet Core registers the same path.
        assertEquals("DBus3bamQjgJULBJtYXpEzDWQRwF5iwxgC", address.stringValue)
        assertEquals(ScriptType.P2PKH, address.scriptType)
        assertTrue(DogecoinKit.validateWatchAddress(address.stringValue))
        assertFalse(DogecoinKit.validateWatchAddress("AETZJzedcmLM2rxCM6VqCGF3YEMUjA3jMw"))
    }

    @Test
    fun `legacy P2PKH signature and raw transaction match independent secp256k1 vector`() {
        val hdWallet = HDWallet(seed, 3, Purpose.BIP44)
        val wallet = Wallet(hdWallet, 20)
        val publicKey = wallet.publicKey(0, 0, true)
        assertEquals(
            "02cc6b0dc33aabcf3a23643e5e2919a80c50fb3dd2129ce409bbc5f0d4643d05e0",
            publicKey.publicKey.toHexString(),
        )

        val previousHash = "11".repeat(32).hexToByteArray()
        val input = TransactionInput(previousHash, 0, sequence = 0xffffffffL)
        val previousOutput = TransactionOutput(
            value = 200_000_000,
            index = 0,
            script = DogecoinKit.firstAddress(seed).lockingScript,
            type = ScriptType.P2PKH,
        ).apply { transactionHash = previousHash }
        val destination = Base58AddressConverter(30, 22)
            .convert("DLSSSUS3ex7YNDACJDxMER1ZMW579Vy8Zy")
        val output = TransactionOutput(
            value = 100_000_000,
            index = 0,
            script = destination.lockingScript,
            type = ScriptType.P2PKH,
        )
        val transaction = Transaction(version = 1, lockTime = 0)
        val inputToSign = InputToSign(input, previousOutput, publicKey)

        val signatureData = EcdsaInputSigner(wallet, MainNetDogecoin())
            .sigScriptData(transaction, listOf(inputToSign), listOf(output), 0)
        assertEquals(
            "3045022100e46998b70b3db0c19454f6713f42605475d80a876383cf970f8c8c10656c611f02202ef9f132e167b5d452fcdfa82768852dfc7cdc27847049d5e6a229ff94e04afe01",
            signatureData[0].toHexString(),
        )

        input.sigScript = OpCodes.push(signatureData[0]) + OpCodes.push(signatureData[1])
        val fullTransaction = FullTransaction(transaction, listOf(input), listOf(output))
        assertEquals(EXPECTED_RAW_TRANSACTION, TransactionSerializer.serialize(fullTransaction).toHexString())
        assertEquals("a19dff48fb73696efab2b1716594cde89588b4178652760ed3a6cfcf2886dcb0", fullTransaction.header.hash.toReversedHex())
    }

    companion object {
        private const val EXPECTED_RAW_TRANSACTION =
            "01000000011111111111111111111111111111111111111111111111111111111111111111000000006b483045022100e46998b70b3db0c19454f6713f42605475d80a876383cf970f8c8c10656c611f02202ef9f132e167b5d452fcdfa82768852dfc7cdc27847049d5e6a229ff94e04afe012102cc6b0dc33aabcf3a23643e5e2919a80c50fb3dd2129ce409bbc5f0d4643d05e0ffffffff0100e1f505000000001976a914a7d191ec42aa113e28cd858cceaa7c733ba2f77788ac00000000"
    }
}
