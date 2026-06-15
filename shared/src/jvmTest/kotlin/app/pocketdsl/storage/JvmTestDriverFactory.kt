package app.pocketdsl.storage

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.pocketdsl.db.PocketDslDatabase

class JvmTestDriverFactory : DriverFactory {
    override fun createDriver(): SqlDriver {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        PocketDslDatabase.Schema.create(driver)
        return driver
    }
}
