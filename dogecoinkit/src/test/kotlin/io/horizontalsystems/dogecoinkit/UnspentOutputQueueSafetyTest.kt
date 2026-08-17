package io.horizontalsystems.dogecoinkit

import io.horizontalsystems.bitcoincore.DustCalculator
import io.horizontalsystems.bitcoincore.managers.UnspentOutputQueue
import io.horizontalsystems.bitcoincore.models.Block
import io.horizontalsystems.bitcoincore.models.PublicKey
import io.horizontalsystems.bitcoincore.models.Transaction
import io.horizontalsystems.bitcoincore.models.TransactionOutput
import io.horizontalsystems.bitcoincore.storage.UnspentOutput
import io.horizontalsystems.bitcoincore.transactions.TransactionSizeCalculator
import io.horizontalsystems.bitcoincore.transactions.scripts.ScriptType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class UnspentOutputQueueSafetyTest {
    @Test
    fun `input aggregation overflow fails closed`() {
        val queue = queue(value = 1, feeRate = 0)
        queue.push(utxo(Long.MAX_VALUE))
        queue.push(utxo(1))

        assertThrows(ArithmeticException::class.java) {
            queue.calculate()
        }
    }

    @Test
    fun `negative input value fails closed`() {
        val queue = queue(value = 1, feeRate = 0)
        queue.push(utxo(-1))

        assertThrows(ArithmeticException::class.java) {
            queue.calculate()
        }
    }

    @Test
    fun `sender paid value plus fee overflow fails closed`() {
        val queue = queue(value = Long.MAX_VALUE, feeRate = 1)
        queue.push(utxo(Long.MAX_VALUE))

        assertThrows(ArithmeticException::class.java) {
            queue.calculate()
        }
    }

    @Test
    fun `recipient paid value minus fee underflow fails closed`() {
        val queue = queue(value = Long.MIN_VALUE, feeRate = 1, senderPay = false)
        queue.push(utxo(1))

        assertThrows(ArithmeticException::class.java) {
            queue.calculate()
        }
    }

    @Test
    fun `transaction size times fee overflow fails closed`() {
        val sizeCalculator = mock(TransactionSizeCalculator::class.java)
        val output = utxo(Long.MAX_VALUE)
        `when`(
            sizeCalculator.transactionSize(
                listOf(output.output),
                listOf(ScriptType.P2PKH),
                null,
                0,
            )
        ).thenReturn(Long.MAX_VALUE)

        val queue = queue(
            value = 1,
            feeRate = 2,
            sizeCalculator = sizeCalculator,
        )
        queue.push(output)

        assertThrows(ArithmeticException::class.java) {
            queue.calculate()
        }
    }

    @Test
    fun `change size times fee widens before multiplication`() {
        val sizeCalculator = mock(TransactionSizeCalculator::class.java)
        val output = utxo(Long.MAX_VALUE)
        `when`(
            sizeCalculator.transactionSize(
                listOf(output.output),
                listOf(ScriptType.P2PKH),
                null,
                0,
            )
        ).thenReturn(0)
        `when`(sizeCalculator.outputSize(ScriptType.P2PKH)).thenReturn(Int.MAX_VALUE)

        val queue = queue(
            value = 1,
            feeRate = Int.MAX_VALUE,
            sizeCalculator = sizeCalculator,
        )
        queue.push(output)

        val selection = queue.calculate()
        val changeFee = Int.MAX_VALUE.toLong() * Int.MAX_VALUE.toLong()
        assertEquals(Long.MAX_VALUE - 1 - changeFee, selection.changeValue)
    }

    @Test
    fun `negative arithmetic parameters fail closed`() {
        assertThrows(ArithmeticException::class.java) {
            queue(value = -1, feeRate = 0).apply { push(utxo(1)) }.calculate()
        }
        assertThrows(ArithmeticException::class.java) {
            queue(value = 1, feeRate = -1).apply { push(utxo(1)) }.calculate()
        }

        val negativeSizeCalculator = mock(TransactionSizeCalculator::class.java)
        val output = utxo(1)
        `when`(
            negativeSizeCalculator.transactionSize(
                listOf(output.output),
                listOf(ScriptType.P2PKH),
                null,
                0,
            )
        ).thenReturn(-1)
        assertThrows(ArithmeticException::class.java) {
            queue(value = 1, feeRate = 1, sizeCalculator = negativeSizeCalculator)
                .apply { push(output) }
                .calculate()
        }
    }

    @Test
    fun `change dust follows the actual first input script`() {
        val sizeCalculator = TransactionSizeCalculator()
        val dustCalculator = DustCalculator(3_000, sizeCalculator)
        val recipientType = ScriptType.P2WPKH
        val actualChangeType = ScriptType.P2PKH
        val input = utxo(0, actualChangeType)
        val feeRate = 1
        val feeWithoutChange = sizeCalculator.transactionSize(
            previousOutputs = listOf(input.output),
            outputs = listOf(recipientType),
            memo = null,
            pluginDataOutputSize = 0,
        ) * feeRate
        val changeFee = sizeCalculator.outputSize(actualChangeType).toLong() * feeRate
        val remainderBetweenDustThresholds = 400L
        require(remainderBetweenDustThresholds > dustCalculator.dust(recipientType))
        require(remainderBetweenDustThresholds < dustCalculator.dust(actualChangeType))

        val queue = queue(
            value = 1_000,
            feeRate = feeRate,
            senderPay = true,
            outputScriptType = recipientType,
            changeType = recipientType,
            changeToFirstInput = true,
            sizeCalculator = sizeCalculator,
            dustCalculator = dustCalculator,
        )
        queue.push(
            utxo(
                1_000 + feeWithoutChange + changeFee + remainderBetweenDustThresholds,
                actualChangeType,
            )
        )

        assertNull(queue.calculate().changeValue)
    }

    @Test
    fun `recipient and change at exact dust are accepted`() {
        val sizeCalculator = TransactionSizeCalculator()
        val dustCalculator = DustCalculator(3_000, sizeCalculator)
        val dust = dustCalculator.dust(ScriptType.P2PKH).toLong()
        val input = utxo(0)
        val feeRate = 1
        val feeWithoutChange = sizeCalculator.transactionSize(
            previousOutputs = listOf(input.output),
            outputs = listOf(ScriptType.P2PKH),
            memo = null,
            pluginDataOutputSize = 0,
        ) * feeRate
        val changeFee = sizeCalculator.outputSize(ScriptType.P2PKH).toLong() * feeRate

        val exactRecipientQueue = queue(
            value = dust,
            feeRate = feeRate,
            sizeCalculator = sizeCalculator,
            dustCalculator = dustCalculator,
        )
        exactRecipientQueue.push(utxo(dust + feeWithoutChange))
        assertEquals(dust, exactRecipientQueue.calculate().recipientValue)

        val exactChangeQueue = queue(
            value = 1_000,
            feeRate = feeRate,
            sizeCalculator = sizeCalculator,
            dustCalculator = dustCalculator,
        )
        exactChangeQueue.push(utxo(1_000 + feeWithoutChange + changeFee + dust))
        assertEquals(dust, exactChangeQueue.calculate().changeValue)
    }

    private fun queue(
        value: Long,
        feeRate: Int,
        senderPay: Boolean = true,
        outputScriptType: ScriptType = ScriptType.P2PKH,
        changeType: ScriptType = ScriptType.P2PKH,
        changeToFirstInput: Boolean = false,
        sizeCalculator: TransactionSizeCalculator = TransactionSizeCalculator(),
        dustCalculator: DustCalculator = DustCalculator(
            dustRelayTxFee = 0,
            sizeCalculator = sizeCalculator,
            fixedDustThreshold = 1,
        ),
    ) = UnspentOutputQueue(
        UnspentOutputQueue.Parameters(
            value = value,
            senderPay = senderPay,
            memo = null,
            fee = feeRate,
            outputsLimit = null,
            outputScriptType = outputScriptType,
            changeType = changeType,
            pluginDataOutputSize = 0,
            changeToFirstInput = changeToFirstInput,
        ),
        sizeCalculator,
        dustCalculator,
    )

    private fun utxo(
        value: Long,
        scriptType: ScriptType = ScriptType.P2PKH,
    ) = UnspentOutput(
        TransactionOutput(value, 0, byteArrayOf(), scriptType),
        PublicKey(),
        Transaction(),
        Block(),
    )
}
