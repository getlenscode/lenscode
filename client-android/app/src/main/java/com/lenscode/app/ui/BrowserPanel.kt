package com.lenscode.app.ui

import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Browser dock button's prompt (docs/remote-setup-and-limits.md's "Web
 * preview" row: Code has no embedded live preview, so this is the app's only
 * way to open one). Launches as a normal Android activity — in XR that means
 * the system opens Chrome as its own separate spatial window in Home Space,
 * not something this app can dock beside itself or pin to a side; the user
 * repositions it like any other XR window once it appears.
 */
@Composable
fun BrowserLaunchOverlay(
    visible: Boolean,
    url: String,
    onUrlChange: (String) -> Unit,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    isListeningForUrl: Boolean = false,
    onVoiceInput: () -> Unit = {},
) {
    if (!visible) return
    Box(
        Modifier.fillMaxSize().background(Color(0xAA000000)).clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .width(420.dp)
                .glassFinish(PanelCorner)
                .clickable(enabled = false) {}
                .padding(20.dp),
        ) {
            Column {
                Text("Open in Chrome", color = LensCodeColors.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Opens as its own window, requested docked to the right — reposition it if it lands somewhere else.",
                    color = LensCodeColors.Muted,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        PairingTextField(url, onUrlChange, "http://192.168.1.x:3000", onSubmit = onOpen)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (isListeningForUrl) "…" else "🎤",
                        fontSize = 18.sp,
                        modifier = Modifier.clickable(enabled = !isListeningForUrl, onClick = onVoiceInput).padding(6.dp),
                    )
                }
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "Cancel",
                        color = LensCodeColors.Muted,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable(onClick = onDismiss),
                    )
                    Text(
                        "Open",
                        color = LensCodeColors.Blue,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clickable(enabled = url.isNotBlank(), onClick = onOpen),
                    )
                }
            }
        }
    }
}

/**
 * Embedded browser panel — renders inside this app's own window via
 * [WebView] instead of launching Chrome as a separate Android activity.
 * Added because a separate Chrome window occludes/hides this app's other
 * panels (Terminal in particular) in Home Space rather than sitting beside
 * them; see BrowserLaunchOverlay's doc comment for why external Chrome was
 * the original approach and docs/remote-setup-and-limits.md's "Web preview"
 * row for the limitation this closes.
 */
@Composable
fun BrowserPanelContent(
    url: String,
    onChangeUrl: () -> Unit,
    onOpenInChrome: () -> Unit,
) {
    var displayedUrl by remember(url) { mutableStateOf(url) }
    var canGoBack by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableIntStateOf(0) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    Box(Modifier.fillMaxSize()) {
    GlassSurface {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (canGoBack) {
                    Text(
                        "←",
                        color = LensCodeColors.Text,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable { webViewRef?.goBack() }.padding(end = 10.dp),
                    )
                }
                Text(
                    "↻",
                    color = LensCodeColors.Muted,
                    fontSize = 15.sp,
                    modifier = Modifier.clickable { reloadKey++ }.padding(end = 10.dp),
                )
                Text(
                    displayedUrl,
                    color = LensCodeColors.Muted,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "Change",
                    color = LensCodeColors.Cyan,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable(onClick = onChangeUrl).padding(start = 10.dp),
                )
                Text(
                    "Open in Chrome",
                    color = LensCodeColors.Blue,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable(onClick = onOpenInChrome).padding(start = 14.dp),
                )
            }
            if (isLoading || loadError != null) {
                Text(
                    loadError?.let { "Couldn't load this page — $it" } ?: "Loading…",
                    color = if (loadError != null) LensCodeColors.Amber else LensCodeColors.Muted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
            AndroidView(
                modifier = Modifier.fillMaxWidth().weight(1f),
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        // LAN dev servers are almost always same-origin http — mixed
                        // content only comes up if the page itself pulls an https
                        // sub-resource, but default-block would silently blank it.
                        settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView, startedUrl: String, favicon: android.graphics.Bitmap?) {
                                isLoading = true
                                loadError = null
                            }

                            override fun onPageFinished(view: WebView, finishedUrl: String) {
                                isLoading = false
                                displayedUrl = finishedUrl
                                canGoBack = view.canGoBack()
                            }

                            override fun onReceivedError(
                                view: WebView,
                                request: android.webkit.WebResourceRequest,
                                error: android.webkit.WebResourceError,
                            ) {
                                if (!request.isForMainFrame) return
                                isLoading = false
                                loadError = "${error.description} (${error.errorCode})"
                                android.util.Log.w("LensCodeBrowser", "load failed for ${request.url}: ${error.description} (${error.errorCode})")
                            }

                            override fun onReceivedHttpError(
                                view: WebView,
                                request: android.webkit.WebResourceRequest,
                                errorResponse: android.webkit.WebResourceResponse,
                            ) {
                                if (!request.isForMainFrame) return
                                isLoading = false
                                loadError = "HTTP ${errorResponse.statusCode}"
                                android.util.Log.w("LensCodeBrowser", "load failed for ${request.url}: HTTP ${errorResponse.statusCode}")
                            }

                            override fun onReceivedSslError(
                                view: WebView,
                                handler: android.webkit.SslErrorHandler,
                                error: android.net.http.SslError,
                            ) {
                                isLoading = false
                                loadError = "SSL error (${error.primaryError})"
                                android.util.Log.w("LensCodeBrowser", "SSL error for ${error.url}: ${error.primaryError}")
                                handler.cancel() // never silently trust an untrusted cert
                            }
                        }
                        webChromeClient = WebChromeClient()
                        webViewRef = this
                        loadUrl(url)
                    }
                },
                update = { view ->
                    // reloadKey bump is the only signal to actually reload — a
                    // recomposition from e.g. displayedUrl changing shouldn't
                    // also re-navigate and stomp forward/back history.
                    if (reloadKey > 0) view.reload()
                },
            )
        }
    }
    }
}
