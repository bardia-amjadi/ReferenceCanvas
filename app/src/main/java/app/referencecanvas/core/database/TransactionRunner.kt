package app.referencecanvas.core.database

import androidx.room.withTransaction
import javax.inject.Inject

/** Abstraction over Room transactions so repositories stay unit-testable. */
interface TransactionRunner {
    suspend fun <T> run(block: suspend () -> T): T
}

class RoomTransactionRunner @Inject constructor(
    private val db: AppDatabase,
) : TransactionRunner {
    override suspend fun <T> run(block: suspend () -> T): T = db.withTransaction { block() }
}
