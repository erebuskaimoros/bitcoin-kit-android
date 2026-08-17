package io.horizontalsystems.dogecoinkit

import io.horizontalsystems.bitcoincore.extensions.hexToByteArray
import io.horizontalsystems.bitcoincore.extensions.toReversedHex
import io.horizontalsystems.bitcoincore.io.BitcoinInputMarkable
import io.horizontalsystems.bitcoincore.io.BitcoinOutput
import io.horizontalsystems.bitcoincore.crypto.CompactBits
import io.horizontalsystems.bitcoincore.utils.HashUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

class AuxPowBlockHeaderParserTest {

    @Test
    fun `parses and validates Core checkpoint block 5050000`() {
        val header = parser.parse(BitcoinInputMarkable(CORE_5050000_HEADER.hexToByteArray()))

        assertEquals(6_422_788, header.version)
        assertEquals("e7d4577405223918491477db725a393bcfc349d8ee63b0a4fde23cbfbfd81dea", header.hash.toReversedHex())
        assertTrue(header.auxPow)
        assertTrue(header.auxPowValidated)
        assertEquals("00000000000000d6574469fdfcf3f6f451a69302b46fe6db5049ac9c44820761", header.proofOfWorkHash.toHex())
    }

    @Test(expected = AuxPowValidationException::class)
    fun `rejects a corrupted chain commitment`() {
        val bytes = CORE_5050000_HEADER.hexToByteArray()
        val commitment = "e7d4577405223918491477db725a393bcfc349d8ee63b0a4fde23cbfbfd81dea".hexToByteArray()
        val offset = bytes.indexOfSubArray(commitment)
        bytes[offset] = (bytes[offset].toInt() xor 1).toByte()

        parser.parse(BitcoinInputMarkable(bytes))
    }

    @Test(expected = AuxPowValidationException::class)
    fun `rejects parent block carrying Dogecoin chain id`() {
        val bytes = CORE_5050000_HEADER.hexToByteArray()
        val parentOffset = bytes.size - 80
        bytes[parentOffset + 2] = 0x62

        parser.parse(BitcoinInputMarkable(bytes))
    }

    @Test
    fun `testnet AuxPoW expected index uses actual signed child chain id`() {
        val serialized = syntheticAuxPowHeader(childChainId = 7)
        val testParser = DogecoinBlockHeaderParser(
            strictChainId = false,
            powLimit = CompactBits.decode(EASY_BITS),
        )

        val header = testParser.parse(BitcoinInputMarkable(serialized))

        assertTrue(header.auxPowValidated)
        assertEquals(7, DogecoinConsensus.chainId(header.version))
        assertEquals(1, DogecoinBlockHeaderParser.expectedIndex(0, 7, 1))
        assertEquals(0, DogecoinBlockHeaderParser.expectedIndex(0, 98, 1))
    }

    @Test
    fun `Core accepts noncanonical parent coinbase prevout`() {
        val serialized = syntheticAuxPowHeader(
            childChainId = 7,
            parentPreviousOutputHash = ByteArray(32) { 1 },
            parentPreviousOutputIndex = 42,
        )

        val header = easyTestParser().parse(BitcoinInputMarkable(serialized))

        assertTrue(header.auxPowValidated)
    }

    @Test
    fun `Core accepts parent merkle branches longer than thirty two`() {
        val serialized = syntheticAuxPowHeader(
            childChainId = 7,
            parentMerkleBranchSize = 33,
        )

        val header = easyTestParser().parse(BitcoinInputMarkable(serialized))

        assertTrue(header.auxPowValidated)
    }

    @Test(expected = AuxPowValidationException::class)
    fun `rejects noncanonical CompactSize in AuxPoW parent transaction`() {
        parser.parse(
            BitcoinInputMarkable(
                syntheticAuxPowHeader(childChainId = 98, nonCanonicalInputCount = true),
            )
        )
    }

    @Test(expected = AuxPowValidationException::class)
    fun `strict mainnet rejects wrong chain id on non-Aux nonlegacy header`() {
        val header = mineLegacyHeader(version = (7 shl 16) or 3)
        DogecoinBlockHeaderParser(
            strictChainId = true,
            powLimit = CompactBits.decode(EASY_BITS),
        ).parse(BitcoinInputMarkable(header))
    }

    @Test
    fun `strict mainnet permits correct-chain non-Aux nonlegacy header`() {
        val header = mineLegacyHeader(version = (98 shl 16) or 3)
        val parsed = DogecoinBlockHeaderParser(
            strictChainId = true,
            powLimit = CompactBits.decode(EASY_BITS),
        ).parse(BitcoinInputMarkable(header))

        assertEquals(98, DogecoinConsensus.chainId(parsed.version))
        assertTrue(!parsed.auxPow)
    }

    private val parser = DogecoinBlockHeaderParser(strictChainId = true)

    companion object {
        private const val EASY_BITS = 0x207fffffL
        // Serialized CBlockHeader (base 80 bytes + CAuxPow) for the v1.14.9
        // mainnet checkpoint at height 5,050,000. The base header and block id
        // are pinned in src/chainparams.cpp at commit e0a1c157791544e818c901bd9341896965afbf9d.
        const val CORE_5050000_HEADER =
            "04016200c4554161067da8781dea838444895b681cc5e6b31f0a9ae61a4d10c88f18dcdf603f781b32b6ed90fa9c3ebca7a3e94f45192094fc2165d436d3cee45e9e46e7c015a6654195011a00000000" +
            "01000000010000000000000000000000000000000000000000000000000000000000000000ffffffff5603cfea2702002f42696e616e63652f343032fabe6d6de7d4577405223918491477db725a393bcfc349d8ee63b0a4fde23cbfbfd81dea0100000000000000fc7ce3c41bdf3e1074221e7bc5fdfd370200920e22000000ffffffff02088a4125000000001976a91499ce4c0a552c646353cc9e0df4c824ba2d978de588ac0000000000000000266a24aa21a9edaaecbf83657eaa3aa1d21756bff9a3ab27fd2efc00e7474348a5d6b898b74996000000004d1171f4f76689e0cb8e75db3b2339239a168bb047396adb465662bc783f43fc051dc092ee378742ffd407c416cea5e253e4c82f6da8c540c71fd9c2926ad48e7517dbc2900e8ffd7a6e4e34698cf20760e0175a2f30f1bb4a8941df988611f477b7d8cf14f437b7fd2b55f10f6ecf34331a51607719f9304bbe5a80e14b48896867baea51b2512f4af86db0a473619b4aa4a0e4e24fb551c95cdfe6aa226b5d797ed359ed1202edb80f0b2ee15e29a1a8c981328d805932c58f9285d2d2f7885e0000000000000000000000002053e7f451130dfdb5f2a8f01755312e796ae2af09b067135e331cbab36ea846ae41da1a341f4c355d82e9ef0d99a2f50052c38bcab349cf4a8dbb45d33e402c74c115a6659387001a4a962d82"
    }

    private fun syntheticAuxPowHeader(
        childChainId: Int,
        parentPreviousOutputHash: ByteArray = ByteArray(32),
        parentPreviousOutputIndex: Long = 0xffffffffL,
        parentMerkleBranchSize: Int = 0,
        nonCanonicalInputCount: Boolean = false,
    ): ByteArray {
        val childVersion = (childChainId shl 16) or DogecoinConsensus.auxPowVersionFlag or 4
        val childHeader = serializeHeader(childVersion, ByteArray(32), ByteArray(32), 1, EASY_BITS, 0)
        val childHash = HashUtils.doubleSha256(childHeader)
        val sibling = ByteArray(32)
        val chainIndex = DogecoinBlockHeaderParser.expectedIndex(0, childChainId, 1)
        val chainRoot = DogecoinBlockHeaderParser.checkMerkleBranch(childHash, listOf(sibling), chainIndex)

        val script = byteArrayOf(0xfa.toByte(), 0xbe.toByte(), 'm'.code.toByte(), 'm'.code.toByte()) +
            chainRoot.reversedArray() +
            byteArrayOf(2, 0, 0, 0, 0, 0, 0, 0)
        val transactionTail = BitcoinOutput()
            .write(parentPreviousOutputHash)
            .writeUnsignedInt(parentPreviousOutputIndex)
            .writeVarInt(script.size.toLong())
            .write(script)
            .writeUnsignedInt(0xffffffffL)
            .writeVarInt(0)
            .writeUnsignedInt(0)
            .toByteArray()
        val canonicalTransaction = BitcoinOutput()
            .writeInt(1)
            .writeVarInt(1)
            .write(transactionTail)
            .toByteArray()
        val wireTransaction = if (nonCanonicalInputCount) {
            BitcoinOutput()
                .writeInt(1)
                .write(byteArrayOf(0xfd.toByte(), 1, 0))
                .write(transactionTail)
                .toByteArray()
        } else {
            canonicalTransaction
        }
        // The parent commits to the canonical txid. Before strict CompactSize,
        // the parser canonicalized fd0100 to 01 and incorrectly accepted it.
        val transactionHash = HashUtils.doubleSha256(canonicalTransaction)
        val parentMerkleBranch = List(parentMerkleBranchSize) { ByteArray(32) }
        val parentMerkleRoot = DogecoinBlockHeaderParser.checkMerkleBranch(
            transactionHash,
            parentMerkleBranch,
            0,
        )
        val parentHeader = mineHeader(version = 1, merkleRoot = parentMerkleRoot)

        return BitcoinOutput()
            .write(childHeader)
            .write(wireTransaction)
            .write(ByteArray(32))
            .writeVarInt(parentMerkleBranch.size.toLong())
            .apply { parentMerkleBranch.forEach(::write) }
            .writeInt(0)
            .writeVarInt(1)
            .write(sibling)
            .writeInt(chainIndex)
            .write(parentHeader)
            .toByteArray()
    }

    private fun easyTestParser() = DogecoinBlockHeaderParser(
        strictChainId = false,
        powLimit = CompactBits.decode(EASY_BITS),
    )

    private fun mineLegacyHeader(version: Int): ByteArray = mineHeader(version, ByteArray(32))

    private fun mineHeader(version: Int, merkleRoot: ByteArray): ByteArray {
        val target = CompactBits.decode(EASY_BITS)
        for (nonce in 0L..10_000L) {
            val header = serializeHeader(version, ByteArray(32), merkleRoot, 1, EASY_BITS, nonce)
            if (BigInteger(1, DogecoinScryptHasher().hash(header)) <= target) return header
        }
        error("failed to mine deterministic easy test header")
    }

    private fun serializeHeader(
        version: Int,
        previousHash: ByteArray,
        merkleRoot: ByteArray,
        timestamp: Long,
        bits: Long,
        nonce: Long,
    ) = BitcoinOutput()
        .writeInt(version)
        .write(previousHash)
        .write(merkleRoot)
        .writeUnsignedInt(timestamp)
        .writeUnsignedInt(bits)
        .writeUnsignedInt(nonce)
        .toByteArray()
}

private fun ByteArray.indexOfSubArray(needle: ByteArray): Int {
    for (offset in 0..size - needle.size) {
        if (needle.indices.all { this[offset + it] == needle[it] }) return offset
    }
    error("fixture does not contain needle")
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
