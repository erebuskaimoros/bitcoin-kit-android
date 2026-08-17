package io.horizontalsystems.bitcoincore.serializers

import io.horizontalsystems.bitcoincore.core.IHasher
import io.horizontalsystems.bitcoincore.io.BitcoinInputMarkable
import io.horizontalsystems.bitcoincore.storage.BlockHeader

interface IBlockHeaderParser {
    fun parse(input: BitcoinInputMarkable): BlockHeader
}

class BlockHeaderParser(private val hasher: IHasher) : IBlockHeaderParser {

    override fun parse(input: BitcoinInputMarkable): BlockHeader {
        input.mark()
        val payload = input.readBytes(80)
        val hash = hasher.hash(payload)
        input.reset()

        val version = input.readInt()
        val previousBlockHeaderHash = input.readBytes(32)
        val merkleRoot = input.readBytes(32)
        val timestamp = input.readUnsignedInt()
        val bits = input.readUnsignedInt()
        val nonce = input.readUnsignedInt()

        return BlockHeader(version, previousBlockHeaderHash, merkleRoot, timestamp, bits, nonce, hash)
    }
}
