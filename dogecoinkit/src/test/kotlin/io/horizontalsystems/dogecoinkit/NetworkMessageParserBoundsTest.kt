package io.horizontalsystems.dogecoinkit

import io.horizontalsystems.bitcoincore.exceptions.BitcoinException
import io.horizontalsystems.bitcoincore.io.BitcoinInput
import io.horizontalsystems.bitcoincore.io.BitcoinOutput
import io.horizontalsystems.bitcoincore.network.messages.NetworkMessageParser
import io.horizontalsystems.bitcoincore.network.messages.UnknownMessage
import io.horizontalsystems.bitcoincore.network.Network
import io.horizontalsystems.bitcoincore.utils.HashUtils
import java.io.ByteArrayInputStream
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NetworkMessageParserBoundsTest {
    @Test
    fun `network protocol limit preserves inv capacity and larger block networks`() {
        assertEquals(4_000_000, Network.maxMessagePayloadSize(1_000_000))
        assertEquals(4_000_000, MainNetDogecoin().maxMessagePayloadSize)
        assertEquals(32 * 1024 * 1024, Network.maxMessagePayloadSize(32 * 1024 * 1024))
    }

    @Test
    fun `negative payload length is rejected before allocation`() {
        val parser = NetworkMessageParser(MAGIC, maxPayloadLength = 4)

        assertThrows(BitcoinException::class.java) {
            parser.parseMessage(input(frame(payloadLength = -1)))
        }
    }

    @Test
    fun `payload above configured bound is rejected before reading body`() {
        val parser = NetworkMessageParser(MAGIC, maxPayloadLength = 4)

        assertThrows(BitcoinException::class.java) {
            parser.parseMessage(input(frame(payloadLength = 5)))
        }
    }

    @Test
    fun `payload exactly at configured bound is accepted`() {
        val parser = NetworkMessageParser(MAGIC, maxPayloadLength = 4)
        val payload = byteArrayOf(1, 2, 3, 4)

        val message = parser.parseMessage(input(frame(payload.size, payload)))

        assertTrue(message is UnknownMessage)
    }

    private fun input(bytes: ByteArray) = BitcoinInput(ByteArrayInputStream(bytes))

    private fun frame(payloadLength: Int, payload: ByteArray = byteArrayOf()): ByteArray {
        val command = ByteArray(12).apply {
            "unknown".toByteArray().copyInto(this)
        }
        val checksum = if (payload.size == payloadLength && payloadLength >= 0) {
            HashUtils.doubleSha256(payload).copyOfRange(0, 4)
        } else {
            ByteArray(4)
        }
        return BitcoinOutput()
            .writeInt32(MAGIC)
            .write(command)
            .writeInt(payloadLength)
            .write(checksum)
            .write(payload)
            .toByteArray()
    }

    companion object {
        private const val MAGIC = 0x0b110907L
    }

}
