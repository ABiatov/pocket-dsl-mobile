package app.pocketdsl.ui

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import java.io.ByteArrayInputStream

@Composable
actual fun ArticleHtmlView(
    html: String,
    modifier: Modifier,
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.allowFileAccessFromFileURLs = false
                settings.allowUniversalAccessFromFileURLs = false
                settings.domStorageEnabled = false
                settings.databaseEnabled = false
                webViewClient = LockedDownWebViewClient()
            }
        },
        update = { webView ->
            if (webView.tag != html) {
                webView.tag = html
                webView.loadDataWithBaseURL(
                    "about:blank",
                    htmlDocument(html),
                    "text/html",
                    "UTF-8",
                    null,
                )
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
