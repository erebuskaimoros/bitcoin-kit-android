package io.horizontalsystems.bitcoincore.storage.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object Migration_19_20 : Migration(19, 20) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            "ALTER TABLE `Transaction` ADD COLUMN `isCoinbase` INTEGER NOT NULL DEFAULT 0"
        )
        database.execSQL(
            "ALTER TABLE `InvalidTransaction` ADD COLUMN `isCoinbase` INTEGER NOT NULL DEFAULT 0"
        )
    }
}
