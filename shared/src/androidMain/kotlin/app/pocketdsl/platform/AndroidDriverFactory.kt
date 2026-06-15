package app.pocketdsl.platform

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import app.pocketdsl.db.PocketDslDatabase
import app.pocketdsl.storage.DriverFactory

class AndroidDriverFactory(
    private val context: Context,
) : DriverFactory {
    override fun createDriver(): SqlDriver =
        AndroidSqliteDriver(
            schema = PocketDslDatabase.Schema,
            context = context.applicationContext,
            name = DATABASE_NAME,
        )

    private companion object {
        const val DATABASE_NAME = "pocketdsl.db"
    }
}
