package com.mideyy7.retrieve.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Upgrades a real v1 database (built from app/schemas/.../1.json) to the current version and
 * checks existing rows survive. Room validates the migrated schema against the current one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), RetrieveDatabase::class.java)

    @Test
    fun v1ToV2KeepsChunksAndDefaultsOcrToFalse() {
        helper.createDatabase(DB, 1).use { db ->
            db.execSQL(
                "INSERT INTO documents (id, sha256, name, module, week, type, status, sourceUri, importedAt, error) " +
                    "VALUES (1, 'abc', 'w4.pdf', 'COMP26120', 4, 'SLIDES', 'READY', 'content://x', 0, NULL)",
            )
            db.execSQL(
                "INSERT INTO chunks (id, stableKey, docId, module, week, page, startMs, text, embedding) " +
                    "VALUES (1, 'abc:p1:0', 1, 'COMP26120', 4, 1, NULL, 'Raft log matching', NULL)",
            )
        }

        helper.runMigrationsAndValidate(DB, 2, true).use { db ->
            db.query("SELECT text, ocr FROM chunks WHERE id = 1").use { c ->
                c.moveToFirst()
                assertEquals("Raft log matching", c.getString(0))
                assertEquals(0, c.getInt(1))
            }
        }
    }

    private companion object {
        const val DB = "migration-test.db"
    }
}
