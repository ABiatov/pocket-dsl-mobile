package app.pocketdsl.storage

import app.cash.sqldelight.db.SqlDriver

interface DriverFactory {
    fun createDriver(): SqlDriver
}
