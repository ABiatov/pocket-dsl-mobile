package app.pocketdsl.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
actual fun ArticleHtmlView(
    html: String,
    modifier: Modifier,
) {
    Box(modifier = modifier.verticalScroll(rememberScrollState())) {
        Text(html)
    }
}
