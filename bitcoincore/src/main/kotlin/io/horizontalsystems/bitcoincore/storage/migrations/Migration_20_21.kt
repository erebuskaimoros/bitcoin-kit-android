package io.horizontalsystems.bitcoincore.storage.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

// Adds the persisted coinbase flag used for coinbase-maturity checks. Earlier
// local builds of the Dogecoin kit shipped this column as their own 19 -> 20
// step, which now belongs to upstream; skip a column that already exists so
// those databases still open. Room validates the resulting schema either way.
object Migration_20_21 : Migration(20, 21) {
    override fun migrate(database: SupportSQLiteDatabase) {
        listOf("Transaction", "InvalidTransaction").forEach { table ->
            if (!hasColumn(database, table, "isCoinbase")) {
                database.execSQL(
                    "ALTER TABLE `$table` ADD COLUMN `isCoinbase` INTEGER NOT NULL DEFAULT 0"
                )
            }
        }
    }

    private fun hasColumn(database: SupportSQLiteDatabase, table: String, column: String): Boolean =
        database.query("PRAGMA table_info(`$table`)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            var found = false
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == column) {
                    found = true
                    break
                }
            }
            found
        }
}
