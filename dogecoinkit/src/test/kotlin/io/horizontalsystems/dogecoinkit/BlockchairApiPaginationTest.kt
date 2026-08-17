package io.horizontalsystems.dogecoinkit

import com.eclipsesource.json.Json
import com.eclipsesource.json.JsonArray
import com.eclipsesource.json.JsonObject
import com.eclipsesource.json.JsonValue
import io.horizontalsystems.bitcoincore.apisync.blockchair.BlockchairApi
import io.horizontalsystems.bitcoincore.apisync.blockchair.BlockchairTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockchairApiPaginationTest {

    @Test
    fun `restore paginates after ten thousand rows using unfiltered offset`() {
        val offsets = mutableListOf<Int>()
        val transport = object : BlockchairTransport {
            override fun get(resource: String): JsonValue {
                val offset = resource.substringAfter("offset=").toInt()
                offsets += offset
                return page(
                    address = "DAddress",
                    start = offset,
                    count = if (offset == 0) 10_000 else 1,
                    // The first page is deliberately below stopHeight. The old
                    // recursive code advanced by zero and requested offset 0 forever.
                    blockHeight = if (offset == 0) 5 else 20,
                )
            }

            override fun post(resource: String, body: String): JsonValue = Json.NULL
        }

        val transactions = BlockchairApi("dogecoin", transport)
            .transactions(listOf("DAddress"), stopHeight = 10)

        assertEquals(listOf(0, 10_000), offsets)
        assertEquals(1, transactions.size)
        assertEquals(20, transactions.single().blockHeight)
    }

    @Test
    fun `restore chunks more than one hundred addresses`() {
        val requestedChunks = mutableListOf<List<String>>()
        val transport = object : BlockchairTransport {
            override fun get(resource: String): JsonValue {
                requestedChunks += resource.substringAfter("addresses/")
                    .substringBefore('?')
                    .split(',')
                return emptyPage()
            }

            override fun post(resource: String, body: String): JsonValue = Json.NULL
        }
        val addresses = (0..200).map { "D$it" }

        BlockchairApi("dogecoin", transport).transactions(addresses, null)

        assertEquals(listOf(100, 100, 1), requestedChunks.map { it.size })
        assertTrue(requestedChunks.flatten().containsAll(addresses))
    }

    private fun page(address: String, start: Int, count: Int, blockHeight: Int): JsonObject {
        val transactions = JsonArray()
        repeat(count) { index ->
            transactions.add(
                JsonObject()
                    .add("hash", "tx-${start + index}")
                    .add("block_id", blockHeight)
                    .add("balance_change", 1)
                    .add("address", address)
            )
        }
        return response(address, transactions)
    }

    private fun emptyPage() = response("DAddress", JsonArray())

    private fun response(address: String, transactions: JsonArray): JsonObject = JsonObject().add(
        "data",
        JsonObject()
            .add("addresses", JsonObject().add(address, JsonObject().add("script_hex", "76a9")))
            .add("transactions", transactions)
    )
}
