package app.pocketdsl.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
expect fun ArticleHtmlView(
    html: String,
    modifier: Modifier = Modifier,
)
