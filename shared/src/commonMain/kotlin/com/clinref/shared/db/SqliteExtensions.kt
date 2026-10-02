package com.clinref.shared.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement

/**
 * Executes a prepared query on [SQLiteConnection], iterates over all matching rows,
 * transforms each row using [transform], and automatically closes the statement.
 */
inline fun <T> SQLiteConnection.useQuery(
    sql: String,
    bind: (SQLiteStatement) -> Unit = {},
    transform: (SQLiteStatement) -> T
): List<T> {
    val stmt = prepare(sql)
    return try {
        bind(stmt)
        val list = mutableListOf<T>()
        while (stmt.step()) {
            list.add(transform(stmt))
        }
        list
    } finally {
        stmt.close()
    }
}

/**
 * Executes a prepared query on [SQLiteConnection], transforms the first matching row if present,
 * and automatically closes the statement.
 */
inline fun <T> SQLiteConnection.useQueryFirstOrNull(
    sql: String,
    bind: (SQLiteStatement) -> Unit = {},
    transform: (SQLiteStatement) -> T
): T? {
    val stmt = prepare(sql)
    return try {
        bind(stmt)
        if (stmt.step()) transform(stmt) else null
    } finally {
        stmt.close()
    }
}

/**
 * Executes a non-query prepared statement on [SQLiteConnection] (e.g., INSERT, UPDATE, PRAGMA)
 * and automatically closes the statement.
 */
inline fun SQLiteConnection.useExecute(
    sql: String,
    bind: (SQLiteStatement) -> Unit = {}
) {
    val stmt = prepare(sql)
    try {
        bind(stmt)
        stmt.step()
    } finally {
        stmt.close()
    }
}
