package io.horizontalsystems.dogecoinkit

import io.horizontalsystems.bitcoincore.core.IHasher
import io.horizontalsystems.bitcoincore.utils.HashUtils

/** scrypt_1024_1_1_256, returned in display/big-endian order. */
class DogecoinScryptHasher : IHasher {
    override fun hash(data: ByteArray): ByteArray =
        HashUtils.scrypt(data, data, 1024, 1, 1, 32).reversedArray()
}
