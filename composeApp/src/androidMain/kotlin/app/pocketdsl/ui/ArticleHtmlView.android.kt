package app.pocketdsl.ui

import android.util.Log
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import java.io.ByteArrayInputStream

@Composable
actual fun ArticleHtmlView(
    html: String,
    modifier: Modifier,
) {
    var renderError by remember(html) { mutableStateOf<String?>(null) }

    val error = renderError
    if (error != null) {
        Text(
            text = error,
            modifier = modifier.padding(12.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        return
    }

    AndroidView<View>(
        modifier = modifier,
        factory = { context ->
            try {
                WebView(context).apply {
                    settings.javaScriptEnabled = false
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.allowFileAccessFromFileURLs = false
                    settings.allowUniversalAccessFromFileURLs = false
                    settings.domStorageEnabled = false
                    settings.databaseEnabled = false
                    settings.blockNetworkLoads = true
                    settings.blockNetworkImage = true
                    settings.loadsImagesAutomatically = false
                    settings.setSupportMultipleWindows(false)
                    webViewClient = LockedDownWebViewClient()
                }
            } catch (cause: Throwable) {
                logWebViewFailure("create_webview", cause)
                TextView(context).apply {
                    text = ARTICLE_RENDER_ERROR
                }
            }
        },
        update = { view ->
            val webView = view as? WebView ?: return@AndroidView
            if (webView.tag != html) {
                try {
                    webView.tag = html
                    webView.loadDataWithBaseURL(
                        "about:blank",
                        htmlDocument(html),
                        "text/html",
                        "UTF-8",
                        null,
                    )
                } catch (cause: Throwable) {
                    webView.tag = null
                    logWebViewFailure("load_article_html", cause)
                    renderError = ARTICLE_RENDER_ERROR
                }
            }
        },
    )
}

private class LockedDownWebViewClient : WebViewClient() {
    override fun shouldOverrideUrlLoading(
        view: WebView?,
        request: WebResourceRequest?,
    ): Boolean = true

    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest?,
    ): WebResourceResponse? {
        val url = request?.url?.toString() ?: return emptyResponse()
        return if (url == "about:blank") null else emptyResponse()
    }

    private fun emptyResponse(): WebResourceResponse =
        WebResourceResponse(
            "text/plain",
            "UTF-8",
            ByteArrayInputStream(ByteArray(0)),
        )
}

private fun htmlDocument(articleHtml: String): String =
    """
    <!doctype html>
    <html>
      <head>
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <style>
          body {
            color: #1b1b1f;
            font-family: sans-serif;
            font-size: 16px;
            line-height: 1.45;
            margin: 0;
            padding: 12px;
          }
        </style>
      </head>
      <body>$articleHtml</body>
    </html>
    """.trimIndent()

private fun logWebViewFailure(action: String, cause: Throwable) {
    Log.e(
        LOG_TAG,
        "Article WebView failed: action=$action exception=${cause::class.java.simpleName}: ${cause.message}",
    )
}

private const val LOG_TAG = "PocketDsl"
private const val ARTICLE_RENDER_ERROR = "Article could not be rendered on this device."
