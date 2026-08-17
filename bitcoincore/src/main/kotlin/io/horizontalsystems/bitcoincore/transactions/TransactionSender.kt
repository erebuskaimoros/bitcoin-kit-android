package io.horizontalsystems.bitcoincore.transactions

import io.horizontalsystems.bitcoincore.BitcoinCore
import io.horizontalsystems.bitcoincore.apisync.blockchair.BlockchairApi
import io.horizontalsystems.bitcoincore.core.IInitialDownload
import io.horizontalsystems.bitcoincore.core.IStorage
import io.horizontalsystems.bitcoincore.extensions.toHexString
import io.horizontalsystems.bitcoincore.models.SentTransaction
import io.horizontalsystems.bitcoincore.network.peer.IPeerTaskHandler
import io.horizontalsystems.bitcoincore.network.peer.Peer
import io.horizontalsystems.bitcoincore.network.peer.PeerGroup
import io.horizontalsystems.bitcoincore.network.peer.PeerManager
import io.horizontalsystems.bitcoincore.network.peer.task.PeerTask
import io.horizontalsystems.bitcoincore.network.peer.task.SendTransactionTask
import io.horizontalsystems.bitcoincore.serializers.TransactionSerializer
import io.horizontalsystems.bitcoincore.storage.FullTransaction

class TransactionSender(
    private val transactionSyncer: TransactionSyncer,
    private val peerManager: PeerManager,
    private val initialBlockDownload: IInitialDownload,
    private val storage: IStorage,
    private val timer: TransactionSendTimer,
    private val sendType: BitcoinCore.SendType,
    private val transactionSerializer: TransactionSerializer,
    private val maxRetriesCount: Int = 3,
    private val retriesPeriod: Int = 60,
    private val broadcastFailurePolicy: BroadcastFailurePolicy = BroadcastFailurePolicy.InvalidateAfterRetries,
) : IPeerTaskHandler, TransactionSendTimer.Listener {

    fun sendPendingTransactions() {
        try {
            val transactions = transactionSyncer.getNewTransactions()
            if (transactions.isEmpty()) {
                timer.stop()
                return
            }

            val transactionsToSend = getTransactionsToSend(transactions)
            if (transactionsToSend.isNotEmpty()) {
                send(transactionsToSend)
            } else if (transactions.all(::hasExhaustedRetries)) {
                timer.stop()
            }

        } catch (e: PeerGroup.Error) {
//            logger.warning("Handling pending transactions failed with: ${e.message}")
        }
    }

    fun canSendTransaction() {
        if (getPeersToSend().isEmpty()) {
            throw PeerGroup.Error("peers not synced")
        }
    }

    fun transactionsRelayed(transactions: List<FullTransaction>) {
        transactions.forEach { transaction ->
            storage.getSentTransaction(transaction.header.hash)?.let { sentTransaction ->
                storage.deleteSentTransaction(sentTransaction)
            }
        }
    }

    private fun getTransactionsToSend(transactions: List<FullTransaction>): List<FullTransaction> {
        return transactions.filter { transaction ->
            storage.getSentTransaction(transaction.header.hash)?.let { sentTransaction ->
                sentTransaction.retriesCount < maxRetriesCount && sentTransaction.lastSendTime < (System.currentTimeMillis() - retriesPeriod * 1000)
            } ?: true
        }
    }

    private fun hasExhaustedRetries(transaction: FullTransaction): Boolean =
        storage.getSentTransaction(transaction.header.hash)?.retriesCount?.let { retriesCount ->
            retriesCount >= maxRetriesCount
        } ?: false

    private fun getPeersToSend(): List<Peer> {
        if (peerManager.peersCount < minConnectedPeerSize) {
            return emptyList()
        }

        val freeSyncedPeer = initialBlockDownload.syncedPeers
            .sortedBy { it.ready } // not ready first
            .firstOrNull()
            ?: return emptyList()

        val readyPeers = peerManager.readyPears()
            .filter { it != freeSyncedPeer }
            .sortedBy { it.synced } // not synced first

        if (readyPeers.size == 1) {
            return readyPeers
        }

        return readyPeers.take(readyPeers.size / 2)
    }

    private fun send(transactions: List<FullTransaction>) {
        when (sendType) {
            BitcoinCore.SendType.P2P -> {
                sendViaP2P(transactions)
            }

            is BitcoinCore.SendType.API -> {
                sendViaAPI(transactions, sendType.blockchairApi)
            }
        }
    }

    private fun sendViaAPI(transactions: List<FullTransaction>, blockchairApi: BlockchairApi) {
        if (broadcastFailurePolicy == BroadcastFailurePolicy.InvalidateAfterRetries) {
            // Preserve the historical API behavior for existing kits. They did
            // not persist API attempts: any exception invalidated immediately.
            transactions.forEach { transaction ->
                try {
                    val hex = transactionSerializer.serialize(transaction).toHexString()
                    blockchairApi.broadcastTransaction(hex)
                    transactionSyncer.handleRelayed(listOf(transaction))
                } catch (error: Throwable) {
                    transactionSyncer.handleInvalid(transaction)
                }
            }
            return
        }

        timer.startIfNotRunning()
        transactions.forEach { transaction ->
            val sentTransaction = transactionSendStart(transaction)
            try {
                val hex = transactionSerializer.serialize(transaction).toHexString()
                blockchairApi.broadcastTransaction(hex)

                transactionSyncer.handleRelayed(listOf(transaction))
                storage.deleteSentTransaction(sentTransaction)
            } catch (error: Throwable) {
                // A transport failure is ambiguous: the server may have accepted
                // the transaction before the response was lost. Invalidating it
                // would unlock the inputs and permit an accidental conflicting
                // spend. Keep the exact raw transaction pending and retry it a
                // bounded number of times; chain/API sync can later confirm it.
                sentTransaction.retriesCount++
                sentTransaction.sendSuccess = false
                sentTransaction.lastSendTime = System.currentTimeMillis()
                handleAmbiguousFailure(transaction, sentTransaction)
            }
        }
    }

    private fun sendViaP2P(transactions: List<FullTransaction>) {
        val peers = getPeersToSend()
        if (peers.isEmpty()) {
            return
        }

        timer.startIfNotRunning()

        transactions.forEach { transaction ->
            transactionSendStart(transaction)

            peers.forEach { peer ->
                peer.addTask(SendTransactionTask(transaction))
            }
        }
    }

    private fun transactionSendStart(transaction: FullTransaction): SentTransaction {
        val sentTransaction = storage.getSentTransaction(transaction.header.hash)

        return if (sentTransaction == null) {
            SentTransaction(transaction.header.hash).also(storage::addSentTransaction)
        } else {
            sentTransaction.lastSendTime = System.currentTimeMillis()
            sentTransaction.sendSuccess = false
            storage.updateSentTransaction(sentTransaction)
            sentTransaction
        }
    }

    @Synchronized
    private fun transactionSendAttemptFinished(transaction: FullTransaction) {
        val sentTransaction = storage.getSentTransaction(transaction.header.hash)

        if (sentTransaction == null || sentTransaction.sendSuccess) {
            return
        }

        sentTransaction.retriesCount++
        sentTransaction.sendSuccess = true
        // SendTransactionTask completes both when a peer asks for the raw
        // transaction and when its request timer expires. Neither outcome proves
        // rejection: another peer may already have accepted and propagated the
        // transaction. Keep its inputs locked and retain the exact transaction
        // after the bounded broadcast attempts; chain sync is the authority that
        // can later confirm or invalidate it.
        handleAmbiguousFailure(transaction, sentTransaction)
    }

    private fun handleAmbiguousFailure(
        transaction: FullTransaction,
        sentTransaction: SentTransaction,
    ) {
        if (
            sentTransaction.retriesCount >= maxRetriesCount &&
            broadcastFailurePolicy == BroadcastFailurePolicy.InvalidateAfterRetries
        ) {
            transactionSyncer.handleInvalid(transaction)
            storage.deleteSentTransaction(sentTransaction)
        } else {
            storage.updateSentTransaction(sentTransaction)
        }
    }

    // IPeerTaskHandler

    override fun handleCompletedTask(peer: Peer, task: PeerTask): Boolean {
        return when (task) {
            is SendTransactionTask -> {
                transactionSendAttemptFinished(task.transaction)
                true
            }

            else -> false
        }
    }

    // TransactionSendTimer.Listener

    override fun onTimePassed() {
        sendPendingTransactions()
    }

    companion object {
        const val minConnectedPeerSize = 2
    }
}
