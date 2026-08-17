package io.horizontalsystems.dogecoinkit

import io.horizontalsystems.bitcoincore.extensions.hexToByteArray
import io.horizontalsystems.bitcoincore.io.BitcoinInputMarkable
import io.horizontalsystems.bitcoincore.io.BitcoinOutput
import io.horizontalsystems.bitcoincore.network.messages.HeadersMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DogecoinHeadersMessageParserTest {

    @Test
    fun `consumes multiple variable AuxPoW headers`() {
        val serialized = AuxPowBlockHeaderParserTest.CORE_5050000_HEADER.hexToByteArray()
        val payload = BitcoinOutput()
            .writeVarInt(2)
            .write(serialized)
            .writeVarInt(0)
            .write(serialized)
            .writeVarInt(0)
            .toByteArray()

        val message = parser.parseMessage(BitcoinInputMarkable(payload)) as HeadersMessage

        assertEquals(2, message.headers.size)
        assertTrue(message.headers.all { it.auxPowValidated })
        assertTrue(message.headers[0].hash.contentEquals(message.headers[1].hash))
    }

    private val parser = DogecoinHeadersMessageParser(DogecoinBlockHeaderParser(strictChainId = true))
}
