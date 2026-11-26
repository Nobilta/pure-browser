package com.mybrowser.text

import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.saveable.rememberSaveable
import android.webkit.WebResourceRequest
import com.mybrowser.MainActivity
import com.mybrowser.ui.components.BrowserActionPill
import com.mybrowser.ui.shell.BrowserSheetHeader
import com.mybrowser.ui.devtools.rememberSourcePalette
import com.mybrowser.ui.theme.BrowserColors
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
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
import com.mybrowser.ui.shell.SwipeDismissSnackbarHost
import com.mybrowser.ui.shell.BrowserIconAction
import com.mybrowser.ui.shell.FindBar
import com.mybrowser.ui.theme.PureBrowserTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A read-only view for local text content URIs and the original source of a local HTML page.
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
            PureBrowserTheme(themeMode = themeMode) {
                val lightSystemBars = MaterialTheme.colorScheme.surface.luminance() > 0.5f
                SideEffect {
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = lightSystemBars
                        isAppearanceLightNavigationBars = lightSystemBars
                    }
                }
                PlainTextScreen(uri, intent.type, intent.getBooleanExtra(RETURN_TO_PAGE, false), onClose = ::finish)
            }
        }
    }

    companion object {
        const val RETURN_TO_PAGE = "com.mybrowser.text.RETURN_TO_PAGE"
    }

    @Suppress("DEPRECATION")
    private fun streamUri(intent: Intent?): Uri? {
        if (intent?.action != Intent.ACTION_SEND) return null
        return intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
    }
}

@Composable
private fun PlainTextScreen(uri: Uri?, type: String?, returnToPage: Boolean, onClose: () -> Unit) {
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var content by remember(uri) { mutableStateOf<TextContent?>(null) }

    LaunchedEffect(uri) {
        content = if (uri == null) {
            TextContent.Failed(R.string.plain_text_open_failed)
        } else {
            withContext(Dispatchers.IO) { PlainTextLoader.load(context, uri, type) }
        }
    }

    var finding by rememberSaveable(uri) { mutableStateOf(false) }
    var findQuery by rememberSaveable(uri) { mutableStateOf("") }
    var matchCount by remember { mutableIntStateOf(0) }
    var currentMatch by remember { mutableIntStateOf(0) }
    var viewer by remember { mutableStateOf<WebView?>(null) }
    var pageReady by remember { mutableStateOf(false) }
    val closeFind = {
        keyboard?.hide()
        finding = false
        findQuery = ""
        viewer?.clearMatches()
        matchCount = 0
        currentMatch = 0
    }
    val back = { if (finding) closeFind() else onClose() }
    BackHandler(onBack = back)
    LaunchedEffect(viewer, pageReady, finding, findQuery) {
        matchCount = 0
        currentMatch = 0
        if (!finding || findQuery.isEmpty()) viewer?.clearMatches()
        else if (pageReady) {
            delay(200)
            viewer?.findAllAsync(findQuery)
        }
    }

    val ready = content as? TextContent.Ready
    var raw by rememberSaveable(uri) { mutableStateOf(returnToPage) }
    var preview by remember(ready) { mutableStateOf<TextPreview?>(null) }
    LaunchedEffect(ready) {
        val loaded = ready ?: return@LaunchedEffect
        if (loaded.format.hasPreview) {
            preview = withContext(Dispatchers.Default) { textPreview(loaded.body, loaded.format) }
        }
    }
    var rendererGone by remember { mutableStateOf(false) }
    var rendererKey by remember { mutableIntStateOf(0) }
    val destroyedViews = remember { mutableSetOf<WebView>() }
    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(
                WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
            ))) {
                BrowserSheetHeader(
                    title = ready?.title ?: stringResource(R.string.plain_text_viewer),
                    onBack = back,
                ) {
                    if (ready != null) BrowserIconAction(
                        R.drawable.ic_ui_search, stringResource(R.string.ui_find_in_page),
                    ) { finding = true }
                }
                if (ready != null) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 8.dp).height(IntrinsicSize.Min),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (returnToPage || ready.format.hasPreview) {
                            BrowserActionPill(
                                text = stringResource(if (raw) R.string.text_show_preview else R.string.text_show_original),
                                onClick = { if (returnToPage) onClose() else raw = !raw },
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                            )
                        }
                        BrowserActionPill(
                            text = stringResource(R.string.plain_text_copy),
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            onClick = {
                                val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                val copied = manager != null && try {
                                    manager.setPrimaryClip(ClipData.newPlainText("text", ready.body))
                                    true
                                } catch (_: Exception) {
                                    // A full MiB can exceed the Binder clipboard transaction.
                                    false
                                }
                                scope.launch {
                                    snackbar.showSnackbar(context.getString(
                                        if (copied) R.string.plain_text_copied else R.string.plain_text_copy_failed,
                                    ))
                                }
                            },
                        )
                    }
                    if (!raw && preview?.errorMessage != null) {
                        Text(stringResource(preview!!.errorMessage!!),
                            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (finding) FindBar(
                        query = findQuery, onQueryChange = { findQuery = it },
                        matchCount = matchCount, currentMatch = currentMatch,
                        onNext = { viewer?.findNext(true) },
                        onPrevious = { viewer?.findNext(false) }, onClose = closeFind,
                    )
                }
            }
        },
        snackbarHost = { SwipeDismissSnackbarHost(snackbar) },
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
                    val well = BrowserColors.recessedWell.toArgb()
                    val border = BrowserColors.cardBorder.toArgb()
                    val palette = rememberSourcePalette().map { it.toArgb() }
                    val showPreview = !raw && state.format.hasPreview
                    var html by remember(state, raw, preview, background, foreground, well, border, palette) {
                        mutableStateOf<String?>(null)
                    }
                    LaunchedEffect(state, raw, preview, background, foreground, well, border, palette) {
                        val enhanced = preview
                        if (showPreview && enhanced == null) return@LaunchedEffect
                        html = withContext(Dispatchers.Default) {
                            if (showPreview) previewHtml(enhanced!!, background.toArgb(), foreground.toArgb(), well, border, palette)
                            else textHtml(state.body, background.toArgb(), foreground.toArgb())
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
                                    viewer = this
                                    configureTextViewer(background.toArgb())
                                    setFindListener { active, count, done ->
                                        if (done && viewer === this && finding && findQuery.isNotEmpty()) {
                                            matchCount = count
                                            currentMatch = if (count > 0) active + 1 else 0
                                        }
                                    }
                                    webViewClient = object : WebViewClient() {
                                        override fun onPageFinished(view: WebView, url: String?) {
                                            if (viewer === view) pageReady = true
                                        }

                                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                            val target = request.url
                                            if (target.scheme == "about" && target.fragment != null) return false
                                            if (request.isForMainFrame && request.hasGesture() && target.scheme in listOf("https", "http")) {
                                                context.startActivity(Intent(context, MainActivity::class.java)
                                                    .setAction(Intent.ACTION_VIEW).setData(target))
                                            }
                                            return true
                                        }

                                        override fun onRenderProcessGone(
                                            view: WebView,
                                            detail: RenderProcessGoneDetail?,
                                        ): Boolean {
                                            rendererGone = true
                                            viewer = null
                                            pageReady = false
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
                                    pageReady = false
                                    view.tag = document
                                    view.loadDataWithBaseURL(null, document, "text/html", "UTF-8", null)
                                }
                            },
                            onRelease = { view ->
                                if (viewer === view) { viewer = null; pageReady = false }
                                if (destroyedViews.remove(view)) {
                                    // onRenderProcessGone already detached and destroyed it.
                                } else {
                                    view.setFindListener(null)
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
