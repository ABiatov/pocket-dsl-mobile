package app.pocketdsl

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import app.pocketdsl.ui.App

class MainActivity : ComponentActivity() {
    private lateinit var model: AndroidPocketDslAppModel

    private val openDictionary = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val uri = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && uri != null) {
            Thread {
                model.importFile(contentResolver, uri)
            }.start()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = AndroidPocketDslAppModel(applicationContext)
        setContent {
            App(
                state = model.state,
                onImportClick = { openDictionary.launch(openDictionaryIntent()) },
                onQueryChange = model::onQueryChange,
                onSearchSubmit = model::searchSubmitted,
                onSuggestionClick = model::suggestionSelected,
                onResultClick = model::resultSelected,
                onToggleFavorite = model::toggleFavorite,
            )
        }
    }

    override fun onDestroy() {
        model.close()
        super.onDestroy()
    }

    private fun openDictionaryIntent(): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf(
                    "text/plain",
                    "application/octet-stream",
                    "application/gzip",
                    "application/x-gzip",
                    "application/x-bzip2",
                    "application/x-tar",
                ),
            )
        }
}
