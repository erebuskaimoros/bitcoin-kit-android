package io.horizontalsystems.dogecoinkit

import io.horizontalsystems.bitcoincore.io.BitcoinInputMarkable
import io.horizontalsystems.bitcoincore.network.messages.HeadersMessage
import io.horizontalsystems.bitcoincore.network.messages.IMessage
import io.horizontalsystems.bitcoincore.network.messages.IMessageParser

/** Consumes each header's variable CAuxPow before reading its zero tx count. */
class DogecoinHeadersMessageParser(
    private val headerParser: DogecoinBlockHeaderParser,
) : IMessageParser {
    override val command = "headers"

    override fun parseMessage(input: BitcoinInputMarkable): IMessage {
        val count = input.readVarInt()
        require(count in 0..MAX_HEADERS.toLong()) { "invalid headers count: $count" }
        val headers = Array(count.toInt()) {
            val header = headerParser.parse(input)
            require(input.readVarInt() == 0L) { "headers entry has non-zero transaction count" }
            header
        }
        return HeadersMessage(headers)
    }

    companion object {
        private const val MAX_HEADERS = 2_000
    }
}
