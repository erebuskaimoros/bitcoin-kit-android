package io.horizontalsystems.dogecoinkit

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import io.horizontalsystems.bitcoincore.core.PluginManager
import io.horizontalsystems.bitcoincore.managers.UnspentOutputProvider
import io.horizontalsystems.bitcoincore.models.Block
import io.horizontalsystems.bitcoincore.models.PublicKey
import io.horizontalsystems.bitcoincore.models.Transaction
import io.horizontalsystems.bitcoincore.models.TransactionInput
import io.horizontalsystems.bitcoincore.models.TransactionOutput
import io.horizontalsystems.bitcoincore.storage.CoreDatabase
import io.horizontalsystems.bitcoincore.storage.FullTransaction
import io.horizontalsystems.bitcoincore.storage.Storage
import io.horizontalsystems.bitcoincore.storage.UtxoFilters
import io.horizontalsystems.bitcoincore.storage.migrations.Migration_20_21
import io.horizontalsystems.bitcoincore.transactions.scripts.ScriptType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class DogecoinCoinbaseRoomTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var database: CoreDatabase? = null

    @After
    fun closeDatabase() {
        database?.close()
    }

    @Test
    fun `two colliding coinbase inputs remain classified and immature`() {
        val db = Room.inMemoryDatabaseBuilder(context, CoreDatabase::class.java)
            .allowMainThreadQueries()
            .build()
            .also { database = it }
        val storage = Storage(db)
        assertEquals("0", columnDefault(db.openHelper.writableDatabase, "Transaction", "isCoinbase"))
        assertEquals("0", columnDefault(db.openHelper.writableDatabase, "InvalidTransaction", "isCoinbase"))
        val publicKey = publicKey()
        storage.savePublicKeys(listOf(publicKey))

        val firstBlock = block(145_000, 0x10)
        val secondBlock = block(145_001, 0x11)
        storage.saveBlock(firstBlock)
        storage.saveBlock(secondBlock)
        storage.saveBlock(block(145_010, 0x12))

        val first = coinbaseTransaction(firstBlock, publicKey, marker = 1, value = 100_000_000)
        val second = coinbaseTransaction(secondBlock, publicKey, marker = 2, value = 200_000_000)
        storage.addTransaction(first)
        storage.addTransaction(second)

        // The historical TransactionInput primary key collides, proving why
        // maturity classification cannot be reconstructed from those rows.
        assertTrue(storage.getTransactionInputs(first.header.hash).isEmpty())
        assertEquals(1, storage.getTransactionInputs(second.header.hash).size)
        assertTrue(storage.getTransaction(first.header.hash)!!.isCoinbase)
        assertTrue(storage.getTransaction(second.header.hash)!!.isCoinbase)

        val persistedUtxos = storage.getUnspentOutputs()
        assertEquals(2, persistedUtxos.size)
        assertTrue(persistedUtxos.all { it.transaction.isCoinbase })

        val provider = UnspentOutputProvider(
            storage,
            confirmationsThreshold = 1,
            pluginManager = PluginManager(),
            spendabilityPolicy = DogecoinCoinbaseMaturityPolicy(),
        )
        assertTrue(provider.getSpendableUtxo(UtxoFilters()).isEmpty())
        assertEquals(0, provider.getBalance().spendable)
        assertEquals(300_000_000, provider.getBalance().unspendableTimeLocked)
    }

    @Test
    fun `migration adds persisted coinbase flag to valid and invalid transactions`() {
        val databaseName = "dogecoin-migration-20-21.db"
        assertTrue(context.deleteDatabase(databaseName) || !context.getDatabasePath(databaseName).exists())
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(object : SupportSQLiteOpenHelper.Callback(20) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE `Transaction` (`hash` BLOB NOT NULL, PRIMARY KEY(`hash`))")
                        db.execSQL("CREATE TABLE `InvalidTransaction` (`hash` BLOB NOT NULL, PRIMARY KEY(`hash`))")
                        db.execSQL("INSERT INTO `Transaction` (`hash`) VALUES (x'01')")
                        db.execSQL("INSERT INTO `InvalidTransaction` (`hash`) VALUES (x'02')")
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )

        try {
            val db = helper.writableDatabase
            Migration_20_21.migrate(db)

            assertTrue(columns(db, "Transaction").contains("isCoinbase"))
            assertTrue(columns(db, "InvalidTransaction").contains("isCoinbase"))
            assertEquals("0", columnDefault(db, "Transaction", "isCoinbase"))
            assertEquals("0", columnDefault(db, "InvalidTransaction", "isCoinbase"))
            assertFalse(booleanValue(db, "Transaction"))
            assertFalse(booleanValue(db, "InvalidTransaction"))
            db.version = 21
        } finally {
            helper.close()
        }

        val reopened = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(object : SupportSQLiteOpenHelper.Callback(21) {
                    override fun onCreate(db: SupportSQLiteDatabase) =
                        error("migrated database should be reopened, not recreated")

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                        error("migration version was not persisted")
                })
                .build()
        )
        try {
            val db = reopened.writableDatabase
            assertEquals("0", columnDefault(db, "Transaction", "isCoinbase"))
            assertEquals("0", columnDefault(db, "InvalidTransaction", "isCoinbase"))
            assertFalse(booleanValue(db, "Transaction"))
            assertFalse(booleanValue(db, "InvalidTransaction"))
        } finally {
            reopened.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun `migration keeps an existing coinbase flag from earlier local builds`() {
        val databaseName = "dogecoin-migration-20-21-existing.db"
        assertTrue(context.deleteDatabase(databaseName) || !context.getDatabasePath(databaseName).exists())
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(object : SupportSQLiteOpenHelper.Callback(20) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE `Transaction` (`hash` BLOB NOT NULL, `isCoinbase` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`hash`))")
                        db.execSQL("CREATE TABLE `InvalidTransaction` (`hash` BLOB NOT NULL, `isCoinbase` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`hash`))")
                        db.execSQL("INSERT INTO `Transaction` (`hash`, `isCoinbase`) VALUES (x'01', 1)")
                        db.execSQL("INSERT INTO `InvalidTransaction` (`hash`, `isCoinbase`) VALUES (x'02', 1)")
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )

        try {
            val db = helper.writableDatabase
            Migration_20_21.migrate(db)

            assertEquals("0", columnDefault(db, "Transaction", "isCoinbase"))
            assertEquals("0", columnDefault(db, "InvalidTransaction", "isCoinbase"))
            assertTrue(booleanValue(db, "Transaction"))
            assertTrue(booleanValue(db, "InvalidTransaction"))
        } finally {
            helper.close()
            context.deleteDatabase(databaseName)
        }
    }

    private fun columns(db: SupportSQLiteDatabase, table: String): Set<String> =
        db.query("PRAGMA table_info(`$table`)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            buildSet {
                while (cursor.moveToNext()) add(cursor.getString(nameIndex))
            }
        }

    private fun booleanValue(db: SupportSQLiteDatabase, table: String): Boolean =
        db.query("SELECT `isCoinbase` FROM `$table` LIMIT 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getInt(0) != 0
        }

    private fun columnDefault(db: SupportSQLiteDatabase, table: String, column: String): String? =
        db.query("PRAGMA table_info(`$table`)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            val defaultIndex = cursor.getColumnIndexOrThrow("dflt_value")
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == column) return@use cursor.getString(defaultIndex)
            }
            error("missing $table.$column")
        }

    private fun coinbaseTransaction(
        block: Block,
        publicKey: PublicKey,
        marker: Int,
        value: Long,
    ): FullTransaction {
        val header = Transaction(version = 1).apply {
            blockHash = block.headerHash
            status = Transaction.Status.RELAYED
        }
        val input = TransactionInput(
            previousOutputTxHash = ByteArray(32),
            previousOutputIndex = 0xffffffffL,
            sigScript = byteArrayOf(marker.toByte()),
            sequence = 0xffffffffL,
        )
        val output = TransactionOutput(
            value = value,
            index = 0,
            script = ByteArray(25) { marker.toByte() },
            type = ScriptType.P2PKH,
            publicKey = publicKey,
        )
        return FullTransaction(header, listOf(input), listOf(output))
    }

    private fun publicKey() = PublicKey().apply {
        path = "0/0/0"
        account = 0
        index = 0
        external = true
        publicKeyHash = ByteArray(20) { 0x21 }
        publicKey = ByteArray(33) { 0x22 }
        scriptHashP2WPKH = ByteArray(20) { 0x23 }
        convertedForP2TR = ByteArray(32) { 0x24 }
    }

    private fun block(height: Int, marker: Int) = Block().apply {
        this.height = height
        headerHash = ByteArray(32) { marker.toByte() }
        previousBlockHash = ByteArray(32) { (marker - 1).toByte() }
        merkleRoot = ByteArray(32)
        timestamp = height.toLong()
        bits = 0x1e0ffff0
    }
}
