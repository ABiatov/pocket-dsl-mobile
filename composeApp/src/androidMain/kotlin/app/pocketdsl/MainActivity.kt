package app.pocketdsl

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import app.pocketdsl.ui.App

class MainActivity : ComponentActivity() {
    private lateinit var model: AndroidPocketDslAppModel

    private val openDictionary = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
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
                onImportClick = { openDictionary.launch(arrayOf("*/*")) },
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
}
