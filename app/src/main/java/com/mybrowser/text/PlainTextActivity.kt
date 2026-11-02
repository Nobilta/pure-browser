package com.mybrowser.text

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import android.webkit.WebViewClient
import com.mybrowser.R
import com.mybrowser.data.BrowserPreferencesRepository
import com.mybrowser.data.ThemeMode
import com.mybrowser.ui.theme.MyBrowserTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A read-only view for local `text/plain` content URIs.
 *
 * The browser deliberately keeps WebView's file/content access off; this activity reads the
 * picked file, escapes it into a local HTML document, and loads only that data URL. JavaScript,
 * file access and network loads stay disabled in this WebView.
 */
class PlainTextActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val uri = intent?.data ?: streamUri(intent)
        val themeMode = runCatching { BrowserPreferencesRepository(this).load().theme }
            .getOrDefault(ThemeMode.SYSTEM)
        setContent {
            MyBrowserTheme(themeMode = themeMode) {
                val lightSystemBars = MaterialTheme.colorScheme.surface.luminance() > 0.5f
                SideEffect {
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = lightSystemBars
                        isAppearanceLightNavigationBars = lightSystemBars
                    }
                }
                PlainTextScreen(uri, onClose = ::finish)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun streamUri(intent: Intent?): Uri? {
        if (intent?.action != Intent.ACTION_SEND) return null
        return intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlainTextScreen(uri: Uri?, onClose: () -> Unit) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var content by remember(uri) { mutableStateOf<TextContent?>(null) }

    LaunchedEffect(uri) {
        content = if (uri == null) {
            TextContent.Failed(R.string.plain_text_open_failed)
        } else {
            withContext(Dispatchers.IO) { PlainTextLoader.load(context, uri) }
        }
    }

    BackHandler(onBack = onClose)

    val ready = content as? TextContent.Ready
    var rendererGone by remember { mutableStateOf(false) }
    var rendererKey by remember { mutableStateOf(0) }
    val destroyedViews = remember { mutableSetOf<WebView>() }
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(painterResource(R.drawable.ic_ui_close), stringResource(R.string.ui_close))
                    }
                },
                title = {
                    Text(
                        text = ready?.title ?: stringResource(R.string.plain_text_viewer),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                actions = {
                    if (ready != null) {
                        TextButton(onClick = {
                            val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            val copied = manager != null && try {
                                manager.setPrimaryClip(ClipData.newPlainText("text", ready.body))
                                true
                            } catch (_: Exception) {
                                // A full MiB can exceed the Binder clipboard transaction.
                                false
                            }
                            scope.launch {
                                snackbar.showSnackbar(
                                    context.getString(
                                        if (copied) R.string.plain_text_copied else R.string.plain_text_copy_failed,
                                    ),
                                )
                            }
                        }) {
                            Text(stringResource(R.string.plain_text_copy))
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        when (val state = content) {
            null -> Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }

            is TextContent.Failed -> Text(
                text = stringResource(state.message),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(padding).padding(24.dp),
            )

            is TextContent.Ready -> {
                if (rendererGone) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.plain_text_renderer_gone),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = {
                            rendererGone = false
                            rendererKey++
                        }) {
                            Text(stringResource(R.string.ui_retry))
                        }
                    }
                } else {
                    val background = MaterialTheme.colorScheme.surface
                    val foreground = MaterialTheme.colorScheme.onSurface
                    var html by remember(state.body, background, foreground) { mutableStateOf<String?>(null) }
                    LaunchedEffect(state.body, background, foreground) {
                        html = withContext(Dispatchers.Default) {
                            textHtml(state.body, background.toArgb(), foreground.toArgb())
                        }
                    }
                    val document = html
                    if (document == null) {
                        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    } else key(rendererKey) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize().padding(padding),
                            factory = { viewContext ->
                                WebView(viewContext).apply {
                                    configureTextViewer(background.toArgb())
                                    webViewClient = object : WebViewClient() {
                                        override fun onRenderProcessGone(
                                            view: WebView,
                                            detail: RenderProcessGoneDetail?,
                                        ): Boolean {
                                            rendererGone = true
                                            (view.parent as? ViewGroup)?.removeView(view)
                                            destroyedViews += view
                                            view.destroy()
                                            return true
                                        }
                                    }
                                }
                            },
                            update = { view ->
                                if (view.tag !== document) {
                                    view.tag = document
                                    view.loadDataWithBaseURL(null, document, "text/html", "UTF-8", null)
                                }
                            },
                            onRelease = { view ->
                                if (destroyedViews.remove(view)) {
                                    // onRenderProcessGone already detached and destroyed it.
                                } else {
                                    (view.parent as? ViewGroup)?.removeView(view)
                                    runCatching { view.loadUrl("about:blank") }
                                    view.destroy()
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

private fun WebView.configureTextViewer(backgroundColor: Int) {
    setBackgroundColor(backgroundColor)
    settings.apply {
        javaScriptEnabled = false
        allowFileAccess = false
        allowContentAccess = false
        blockNetworkLoads = true
        domStorageEnabled = false
        loadsImagesAutomatically = false
        setSupportZoom(true)
        builtInZoomControls = false
        displayZoomControls = false
        textZoom = (resources.configuration.fontScale * 100).toInt().coerceIn(50, 200)
    }
}
