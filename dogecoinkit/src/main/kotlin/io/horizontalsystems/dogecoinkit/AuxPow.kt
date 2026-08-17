package io.horizontalsystems.dogecoinkit

import io.horizontalsystems.bitcoincore.crypto.CompactBits
import io.horizontalsystems.bitcoincore.io.BitcoinInputMarkable
import io.horizontalsystems.bitcoincore.io.BitcoinOutput
import io.horizontalsystems.bitcoincore.serializers.IBlockHeaderParser
import io.horizontalsystems.bitcoincore.serializers.TransactionSerializer
import io.horizontalsystems.bitcoincore.storage.BlockHeader
import io.horizontalsystems.bitcoincore.utils.HashUtils
import java.math.BigInteger

class AuxPowValidationException(message: String) : IllegalArgumentException(message)

/**
 * Parses Dogecoin's variable CBlockHeader serialization and validates CAuxPow
 * using the rules in Dogecoin Core v1.14.9 src/auxpow.cpp and dogecoin.cpp.
 */
class DogecoinBlockHeaderParser(
    private val strictChainId: Boolean,
    private val powLimit: BigInteger = DogecoinConsensus.powLimit,
    private val scryptHasher: DogecoinScryptHasher = DogecoinScryptHasher(),
) : IBlockHeaderParser {

    override fun parse(input: BitcoinInputMarkable): BlockHeader {
        val version = input.readInt()
        val previousHash = input.readBytes(32)
        val merkleRoot = input.readBytes(32)
        val timestamp = input.readUnsignedInt()
        val bits = input.readUnsignedInt()
        val nonce = input.readUnsignedInt()

        val baseHeader = serializeHeader(version, previousHash, merkleRoot, timestamp, bits, nonce)
        val blockHash = HashUtils.doubleSha256(baseHeader)
        val carriesAuxPow = DogecoinConsensus.isAuxPow(version)

        if (
            strictChainId &&
            !DogecoinConsensus.isLegacy(version) &&
            DogecoinConsensus.chainId(version) != DogecoinConsensus.chainId
        ) {
            throw AuxPowValidationException("block chain id does not match Dogecoin")
        }

        if (!carriesAuxPow) {
            val proofHash = scryptHasher.hash(baseHeader)
            validateProofOfWork(proofHash, bits)
            return BlockHeader(
                version, previousHash, merkleRoot, timestamp, bits, nonce, blockHash,
                proofOfWorkHash = proofHash,
            )
        }

        val parentCoinbase = try {
            TransactionSerializer.deserialize(input)
        } catch (error: Exception) {
            throw AuxPowValidationException("malformed auxpow parent coinbase: ${error.message}")
        }
        input.readBytes(32) // CMerkleTx.hashBlock; not part of the proof.
        val parentMerkleBranch = readHashVector(
            input,
            "parent merkle branch",
            MAX_CORE_VECTOR_HASHES,
        )
        val parentMerkleIndex = input.readInt()
        val chainMerkleBranch = readHashVector(input, "chain merkle branch", MAX_CHAIN_BRANCH)
        val chainIndex = input.readInt()

        val parentVersion = input.readInt()
        val parentPreviousHash = input.readBytes(32)
        val parentMerkleRoot = input.readBytes(32)
        val parentTimestamp = input.readUnsignedInt()
        val parentBits = input.readUnsignedInt()
        val parentNonce = input.readUnsignedInt()
        val parentHeader = serializeHeader(
            parentVersion,
            parentPreviousHash,
            parentMerkleRoot,
            parentTimestamp,
            parentBits,
            parentNonce,
        )

        if (parentMerkleIndex != 0) {
            throw AuxPowValidationException("auxpow transaction is not the parent coinbase")
        }
        if (strictChainId && DogecoinConsensus.chainId(parentVersion) == DogecoinConsensus.chainId) {
            throw AuxPowValidationException("auxpow parent uses Dogecoin chain id")
        }
        val firstInput = parentCoinbase.inputs.firstOrNull()
            ?: throw AuxPowValidationException("auxpow parent coinbase has no inputs")

        val parentCoinbaseRoot = checkMerkleBranch(
            parentCoinbase.header.hash,
            parentMerkleBranch,
            parentMerkleIndex,
        )
        if (!parentCoinbaseRoot.contentEquals(parentMerkleRoot)) {
            throw AuxPowValidationException("auxpow parent merkle root is incorrect")
        }

        validateChainCommitment(
            childBlockHash = blockHash,
            branch = chainMerkleBranch,
            chainIndex = chainIndex,
            coinbaseScript = firstInput.sigScript,
            childChainId = DogecoinConsensus.chainId(version),
        )

        val proofHash = scryptHasher.hash(parentHeader)
        validateProofOfWork(proofHash, bits)

        return BlockHeader(
            version, previousHash, merkleRoot, timestamp, bits, nonce, blockHash,
            proofOfWorkHash = proofHash,
            auxPow = true,
            auxPowValidated = true,
        )
    }

    private fun validateChainCommitment(
        childBlockHash: ByteArray,
        branch: List<ByteArray>,
        chainIndex: Int,
        coinbaseScript: ByteArray,
        childChainId: Int,
    ) {
        if (chainIndex < 0) throw AuxPowValidationException("negative auxpow chain index")

        val root = checkMerkleBranch(childBlockHash, branch, chainIndex).reversedArray()
        val rootOffset = coinbaseScript.indexOf(root)
        if (rootOffset < 0) throw AuxPowValidationException("missing auxpow chain root")

        val headerOffset = coinbaseScript.indexOf(MERGED_MINING_HEADER)
        if (headerOffset >= 0) {
            if (coinbaseScript.indexOf(MERGED_MINING_HEADER, headerOffset + 1) >= 0) {
                throw AuxPowValidationException("multiple merged-mining headers")
            }
            if (headerOffset + MERGED_MINING_HEADER.size != rootOffset) {
                throw AuxPowValidationException("merged-mining header is not adjacent to chain root")
            }
        } else if (rootOffset > 20) {
            throw AuxPowValidationException("legacy chain root occurs too late in coinbase")
        }

        val metadataOffset = rootOffset + root.size
        if (coinbaseScript.size - metadataOffset < 8) {
            throw AuxPowValidationException("missing auxpow tree size and nonce")
        }
        val treeSize = readUInt32LE(coinbaseScript, metadataOffset)
        val nonce = readUInt32LE(coinbaseScript, metadataOffset + 4)
        val expectedSize = 1L shl branch.size
        if (treeSize != expectedSize) {
            throw AuxPowValidationException("auxpow chain branch size does not match coinbase")
        }
        if (chainIndex != expectedIndex(nonce, childChainId, branch.size)) {
            throw AuxPowValidationException("wrong auxpow chain index")
        }
    }

    private fun validateProofOfWork(displayOrderHash: ByteArray, bits: Long) {
        val target = CompactBits.decode(bits)
        if (target.signum() <= 0 || target > powLimit) {
            throw AuxPowValidationException("proof target is outside Dogecoin range")
        }
        if (BigInteger(1, displayOrderHash) > target) {
            throw AuxPowValidationException("scrypt proof of work does not meet target")
        }
    }

    private fun readHashVector(
        input: BitcoinInputMarkable,
        name: String,
        maximum: Int,
    ): List<ByteArray> {
        val count = input.readVarInt()
        if (count !in 0..maximum.toLong()) {
            throw AuxPowValidationException("$name exceeds safe consensus bound")
        }
        return List(count.toInt()) { input.readBytes(32) }
    }

    companion object {
        private const val MAX_CHAIN_BRANCH = 30
        // Dogecoin Core's CompactSize deserializer permits vectors up to
        // MAX_SIZE (32 MiB). Unlike the chain branch, consensus places no
        // 32-hash restriction on the parent merkle branch.
        private const val MAX_CORE_VECTOR_HASHES = 0x02000000 / 32
        private val MERGED_MINING_HEADER = byteArrayOf(0xfa.toByte(), 0xbe.toByte(), 'm'.code.toByte(), 'm'.code.toByte())

        fun checkMerkleBranch(hash: ByteArray, branch: List<ByteArray>, index: Int): ByteArray {
            if (index < 0) return ByteArray(32)
            var current = hash
            var currentIndex = index
            branch.forEach { sibling ->
                current = if (currentIndex and 1 != 0) {
                    HashUtils.doubleSha256(sibling + current)
                } else {
                    HashUtils.doubleSha256(current + sibling)
                }
                currentIndex = currentIndex ushr 1
            }
            return current
        }

        fun expectedIndex(nonce: Long, chainId: Int, height: Int): Int {
            require(height in 0..30)
            var random = nonce.toUInt()
            random = random * 1_103_515_245u + 12_345u
            random += chainId.toUInt()
            random = random * 1_103_515_245u + 12_345u
            return (random % (1u shl height)).toInt()
        }

        private fun serializeHeader(
            version: Int,
            previousHash: ByteArray,
            merkleRoot: ByteArray,
            timestamp: Long,
            bits: Long,
            nonce: Long,
        ): ByteArray = BitcoinOutput()
            .writeInt(version)
            .write(previousHash)
            .write(merkleRoot)
            .writeUnsignedInt(timestamp)
            .writeUnsignedInt(bits)
            .writeUnsignedInt(nonce)
            .toByteArray()

        private fun readUInt32LE(bytes: ByteArray, offset: Int): Long =
            (bytes[offset].toLong() and 0xff) or
                ((bytes[offset + 1].toLong() and 0xff) shl 8) or
                ((bytes[offset + 2].toLong() and 0xff) shl 16) or
                ((bytes[offset + 3].toLong() and 0xff) shl 24)

        private fun ByteArray.indexOf(needle: ByteArray, fromIndex: Int = 0): Int {
            if (needle.isEmpty()) return fromIndex.coerceAtMost(size)
            for (offset in fromIndex..size - needle.size) {
                if (needle.indices.all { this[offset + it] == needle[it] }) return offset
            }
            return -1
        }
    }
}
