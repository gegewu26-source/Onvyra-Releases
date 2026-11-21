package com.constx.onvyra.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Existing workspaces stay enabled, with files, roots and assistant bindings untouched. */
val Migration_31_32 = object : Migration(31, 32) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE workspaces ADD COLUMN enabled INTEGER NOT NULL DEFAULT 1")
    }
}
