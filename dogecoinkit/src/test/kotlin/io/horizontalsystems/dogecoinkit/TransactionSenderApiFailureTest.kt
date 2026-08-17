package io.horizontalsystems.dogecoinkit

import com.eclipsesource.json.Json
import com.eclipsesource.json.JsonValue
import io.horizontalsystems.bitcoincore.BitcoinCore
import io.horizontalsystems.bitcoincore.apisync.blockchair.BlockchairApi
import io.horizontalsystems.bitcoincore.apisync.blockchair.BlockchairTransport
import io.horizontalsystems.bitcoincore.blocks.InitialBlockDownload
import io.horizontalsystems.bitcoincore.core.IStorage
import io.horizontalsystems.bitcoincore.managers.ApiManagerException
import io.horizontalsystems.bitcoincore.models.SentTransaction
import io.horizontalsystems.bitcoincore.models.Transaction
import io.horizontalsystems.bitcoincore.network.peer.Peer
import io.horizontalsystems.bitcoincore.network.peer.PeerManager
import io.horizontalsystems.bitcoincore.network.peer.task.PeerTask
import io.horizontalsystems.bitcoincore.network.peer.task.SendTransactionTask
import io.horizontalsystems.bitcoincore.serializers.TransactionSerializer
import io.horizontalsystems.bitcoincore.storage.FullTransaction
import io.horizontalsystems.bitcoincore.transactions.TransactionSendTimer
import io.horizontalsystems.bitcoincore.transactions.TransactionSender
import io.horizontalsystems.bitcoincore.transactions.TransactionSyncer
import io.horizontalsystems.bitcoincore.transactions.BroadcastFailurePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class TransactionSenderApiFailureTest {

    @Test
    fun `ambiguous API failure remains pending and retries the same transaction`() {
        val transaction = FullTransaction(Transaction(version = 1), emptyList(), emptyList())
        val syncer = mock(TransactionSyncer::class.java)
        val storage = mock(IStorage::class.java)
        val timer = mock(TransactionSendTimer::class.java)
        var postCount = 0
        `when`(syncer.getNewTransactions()).thenReturn(listOf(transaction))
        `when`(storage.getSentTransaction(transaction.header.hash)).thenReturn(null)

        val api = BlockchairApi("dogecoin", object : BlockchairTransport {
            override fun get(resource: String): JsonValue = Json.NULL
            override fun post(resource: String, body: String): JsonValue {
                postCount++
                throw ApiManagerException.Other("timeout after request body")
            }
        })
        val sender = sender(
            syncer = syncer,
            storage = storage,
            timer = timer,
            api = api,
            // Make the retry immediately eligible without sleeping in a unit test.
            retriesPeriod = -1,
        )

        sender.sendPendingTransactions()

        val persisted = mockingDetails(storage).invocations
            .single { it.method.name == "addSentTransaction" }
            .arguments.single() as SentTransaction
        assertEquals(1, persisted.retriesCount)
        `when`(storage.getSentTransaction(transaction.header.hash)).thenReturn(persisted)

        sender.sendPendingTransactions()

        // The default verification count also proves the retry reused the same
        // persisted record instead of inserting a second broadcast record.
        assertEquals(
            1,
            mockingDetails(storage).invocations.count { it.method.name == "addSentTransaction" },
        )
        verify(syncer, never()).handleInvalid(transaction)
        verify(syncer, never()).handleRelayed(listOf(transaction))
        assertEquals(2, persisted.retriesCount)
        assertEquals(false, persisted.sendSuccess)
        assertEquals(2, postCount)
    }

    @Test
    fun `retry cooldown keeps timer alive`() {
        val transaction = FullTransaction(Transaction(version = 1), emptyList(), emptyList())
        val syncer = mock(TransactionSyncer::class.java)
        val storage = mock(IStorage::class.java)
        val timer = mock(TransactionSendTimer::class.java)
        val pending = SentTransaction(transaction.header.hash).apply {
            retriesCount = 1
            lastSendTime = System.currentTimeMillis()
        }
        `when`(syncer.getNewTransactions()).thenReturn(listOf(transaction))
        `when`(storage.getSentTransaction(transaction.header.hash)).thenReturn(pending)

        val sender = sender(syncer, storage, timer, rejectingApi(), retriesPeriod = 60)

        sender.sendPendingTransactions()

        verify(timer, never()).stop()
        verify(timer, never()).startIfNotRunning()
    }

    @Test
    fun `ambiguous P2P completion at retry limit never unlocks inputs`() {
        val transaction = FullTransaction(Transaction(version = 1), emptyList(), emptyList())
        val syncer = mock(TransactionSyncer::class.java)
        val storage = mock(IStorage::class.java)
        val pending = SentTransaction(transaction.header.hash).apply {
            retriesCount = 2
            sendSuccess = false
        }
        `when`(storage.getSentTransaction(transaction.header.hash)).thenReturn(pending)
        val sender = TransactionSender(
            syncer,
            mock(PeerManager::class.java),
            mock(InitialBlockDownload::class.java),
            storage,
            mock(TransactionSendTimer::class.java),
            BitcoinCore.SendType.P2P,
            TransactionSerializer,
            maxRetriesCount = 3,
            broadcastFailurePolicy = BroadcastFailurePolicy.RetainPending,
        )

        val peer = mock(Peer::class.java)
        val task = SendTransactionTask(transaction)
        task.listener = object : PeerTask.Listener {
            override fun onTaskCompleted(task: PeerTask) {
                sender.handleCompletedTask(peer, task)
            }

            override fun onTaskFailed(task: PeerTask, e: Exception) {
                fail("timeout must remain an ambiguous completion")
            }
        }

        task.handleTimeout()

        assertEquals(3, pending.retriesCount)
        assertEquals(true, pending.sendSuccess)
        verify(storage).updateSentTransaction(pending)
        verify(storage, never()).deleteSentTransaction(pending)
        verify(syncer, never()).handleInvalid(transaction)
    }

    @Test
    fun `timer stops only after all retries are exhausted`() {
        val transaction = FullTransaction(Transaction(version = 1), emptyList(), emptyList())
        val syncer = mock(TransactionSyncer::class.java)
        val storage = mock(IStorage::class.java)
        val timer = mock(TransactionSendTimer::class.java)
        val exhausted = SentTransaction(transaction.header.hash).apply { retriesCount = 3 }
        `when`(syncer.getNewTransactions()).thenReturn(listOf(transaction))
        `when`(storage.getSentTransaction(transaction.header.hash)).thenReturn(exhausted)

        val sender = sender(syncer, storage, timer, rejectingApi(), retriesPeriod = 60)

        sender.sendPendingTransactions()

        verify(timer).stop()
        verify(timer, never()).startIfNotRunning()
    }

    @Test
    fun `default API policy preserves immediate historical invalidation`() {
        val transaction = FullTransaction(Transaction(version = 1), emptyList(), emptyList())
        val syncer = mock(TransactionSyncer::class.java)
        val storage = mock(IStorage::class.java)
        val timer = mock(TransactionSendTimer::class.java)
        `when`(syncer.getNewTransactions()).thenReturn(listOf(transaction))

        val sender = TransactionSender(
            syncer,
            mock(PeerManager::class.java),
            mock(InitialBlockDownload::class.java),
            storage,
            timer,
            BitcoinCore.SendType.API(rejectingApi()),
            TransactionSerializer,
        )

        sender.sendPendingTransactions()

        verify(syncer).handleInvalid(transaction)
        assertEquals(
            0,
            mockingDetails(storage).invocations.count { it.method.name == "addSentTransaction" },
        )
        verify(timer, never()).startIfNotRunning()
    }

    private fun sender(
        syncer: TransactionSyncer,
        storage: IStorage,
        timer: TransactionSendTimer,
        api: BlockchairApi,
        retriesPeriod: Int,
    ) = TransactionSender(
        syncer,
        mock(PeerManager::class.java),
        mock(InitialBlockDownload::class.java),
        storage,
        timer,
        BitcoinCore.SendType.API(api),
        TransactionSerializer,
        maxRetriesCount = 3,
        retriesPeriod = retriesPeriod,
        broadcastFailurePolicy = BroadcastFailurePolicy.RetainPending,
    )

    private fun rejectingApi() = BlockchairApi("dogecoin", object : BlockchairTransport {
        override fun get(resource: String): JsonValue = Json.NULL
        override fun post(resource: String, body: String): JsonValue {
            throw ApiManagerException.Other("not expected")
        }
    })
}
