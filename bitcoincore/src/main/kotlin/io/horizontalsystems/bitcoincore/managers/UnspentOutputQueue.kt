package io.horizontalsystems.bitcoincore.managers

import io.horizontalsystems.bitcoincore.DustCalculator
import io.horizontalsystems.bitcoincore.storage.UnspentOutput
import io.horizontalsystems.bitcoincore.transactions.TransactionSizeCalculator
import io.horizontalsystems.bitcoincore.transactions.scripts.ScriptType

class UnspentOutputQueue(
    private val parameters: Parameters,
    private val sizeCalculator: TransactionSizeCalculator,
    private val dustCalculator: DustCalculator,
) {
    private val changeType: ScriptType
        get() {
            if (parameters.changeToFirstInput) {
                selectedOutputs.firstOrNull()?.let {
                    return it.output.scriptType
                }
            }

            return parameters.changeType
        }

    private var selectedOutputs: MutableList<UnspentOutput> = mutableListOf()
    private var totalValue: Long = 0L
    private var invalidTotalValue = false

    val recipientOutputDust = dustCalculator.dust(parameters.outputScriptType)
    private val changeOutputDust: Int
        get() = dustCalculator.dust(changeType)

    fun push(output: UnspentOutput) {
        selectedOutputs.add(output)
        addToTotal(output.output.value)
        enforceOutputsLimit()
    }

    private fun enforceOutputsLimit() {
        val limit = parameters.outputsLimit
        if (limit != null && limit > 0 && selectedOutputs.size > limit) {
            selectedOutputs.removeFirst()
            recalculateTotal()
        }
    }

    fun set(outputs: List<UnspentOutput>) {
        selectedOutputs.clear()
        totalValue = 0
        invalidTotalValue = false

        outputs.forEach { push(it) }
    }

    private fun addToTotal(value: Long) {
        if (value < 0 || invalidTotalValue) {
            invalidTotalValue = true
            return
        }

        totalValue = try {
            checkedAdd(totalValue, value)
        } catch (_: ArithmeticException) {
            invalidTotalValue = true
            totalValue
        }
    }

    private fun recalculateTotal() {
        totalValue = 0
        invalidTotalValue = false
        selectedOutputs.forEach { addToTotal(it.output.value) }
    }

    private fun checkedAdd(first: Long, second: Long): Long {
        if (
            (second > 0 && first > Long.MAX_VALUE - second) ||
            (second < 0 && first < Long.MIN_VALUE - second)
        ) {
            throw ArithmeticException("Long addition overflow")
        }

        return first + second
    }

    private fun checkedSubtract(first: Long, second: Long): Long {
        if (
            (second > 0 && first < Long.MIN_VALUE + second) ||
            (second < 0 && first > Long.MAX_VALUE + second)
        ) {
            throw ArithmeticException("Long subtraction overflow")
        }

        return first - second
    }

    private fun checkedMultiply(first: Long, second: Long): Long {
        if (first < 0 || second < 0) {
            throw ArithmeticException("Transaction arithmetic parameters must be non-negative")
        }
        if (first != 0L && second > Long.MAX_VALUE / first) {
            throw ArithmeticException("Long multiplication overflow")
        }

        return first * second
    }

    @Throws(SendValueErrors::class, ArithmeticException::class)
    fun calculate(): SelectedUnspentOutputInfo {
        if (selectedOutputs.isEmpty()) {
            throw SendValueErrors.EmptyOutputs
        }
        if (invalidTotalValue) {
            throw ArithmeticException("Unspent output total is invalid")
        }

        val changeDust = changeOutputDust
        if (
            parameters.value < 0 ||
            parameters.fee < 0 ||
            recipientOutputDust < 0 ||
            changeDust < 0
        ) {
            throw ArithmeticException("Transaction arithmetic parameters must be non-negative")
        }

        val feeWithoutChange = calculateFeeWithoutChange()
        val (receiveValue, remainder) = calculateSendValues(feeWithoutChange)

        val changeFee = checkedMultiply(
            sizeCalculator.outputSize(changeType).toLong(),
            parameters.fee.toLong(),
        )
        val actualRemainder = checkedSubtract(remainder, changeFee)

        return if (actualRemainder < changeDust) {
            SelectedUnspentOutputInfo(selectedOutputs, receiveValue, null)
        } else {
            SelectedUnspentOutputInfo(selectedOutputs, receiveValue, actualRemainder)
        }
    }

    private fun calculateFeeWithoutChange(): Long =
        checkedMultiply(
            sizeCalculator.transactionSize(
                previousOutputs = selectedOutputs.map { it.output },
                outputs = listOf(parameters.outputScriptType),
                memo = parameters.memo,
                pluginDataOutputSize = parameters.pluginDataOutputSize
            ),
            parameters.fee.toLong(),
        )

    @Throws(SendValueErrors::class, ArithmeticException::class)
    private fun calculateSendValues(feeWithoutChange: Long): Pair<Long, Long> {
        if (parameters.value < 0 || totalValue < 0 || feeWithoutChange < 0) {
            throw ArithmeticException("Transaction arithmetic parameters must be non-negative")
        }

        val receiveValue = if (parameters.senderPay) {
            parameters.value
        } else {
            checkedSubtract(parameters.value, feeWithoutChange)
        }
        val sentValue = if (parameters.senderPay) {
            checkedAdd(parameters.value, feeWithoutChange)
        } else {
            parameters.value
        }

        if (totalValue < sentValue) {
            throw SendValueErrors.InsufficientUnspentOutputs
        }

        if (receiveValue < recipientOutputDust) {
            throw SendValueErrors.Dust
        }

        val remainder = checkedSubtract(
            checkedSubtract(totalValue, receiveValue),
            feeWithoutChange,
        )

        return Pair(receiveValue, remainder)
    }


    data class Parameters(
        val value: Long,
        val senderPay: Boolean,
        val memo: String?,
        val fee: Int,
        val outputsLimit: Int?,
        val outputScriptType: ScriptType,
        val changeType: ScriptType,
        val pluginDataOutputSize: Int,
        val changeToFirstInput: Boolean,
    )
}
