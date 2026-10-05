package com.momtime.shared.data

/**
 * One database transaction around a block that touches more than one repository. A command that reads and then
 * writes through two repositories (the zone change command moves occurrences and updates templates) must be atomic as
 * a whole: a materialisation run, or one of her actions, that landed between its read and its write would otherwise
 * read half of the change. Every repository call inside [block] joins the transaction, and ADR 0036's serialisation
 * makes a contending run wait for it, so it runs entirely before or entirely after.
 *
 * It is an interface, not a database, so that no caller can name the database (the Koin graph exposes repositories
 * and this, and nothing below them).
 */
interface Transactor {
    fun <T> inTransaction(block: () -> T): T
}

class SqlDelightTransactor(
    private val database: MomTimeDatabase,
) : Transactor {
    override fun <T> inTransaction(block: () -> T): T = database.transactionWithResult { block() }
}
