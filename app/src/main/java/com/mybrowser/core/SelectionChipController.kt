package com.mybrowser.core

import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.util.UUID

/**
 * A small translation affordance anchored to the current text selection.
 *
 * Android exposes no public API for adding an item to the floating text-selection toolbar that
 * WebView uses on Android 13+. Activity.onActionModeStarted is not called for that toolbar, so the
 * native menu item is not reliable. This bridge injects a button next to the selection instead;
 * it works across WebView/OEM versions and never changes the selection before its text is read.
 */
class SelectionChipController(
    private val isCurrent: (WebView) -> Boolean,
    private val onTranslate: (String) -> Unit,
) {
    private data class Bridge(val name: String, val token: String)
    private val bridges = java.util.WeakHashMap<WebView, Bridge>()

    fun install(view: WebView) {
        if (bridges.containsKey(view)) return
        if (!runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) }.getOrDefault(false)) return
        val name = "__pureSelectionBridge" + UUID.randomUUID().toString().replace("-", "")
        val token = UUID.randomUUID().toString()
        val bridge = Bridge(name, token)
        val ok = runCatching {
            WebViewCompat.addWebMessageListener(view, name, setOf("*")) { source, message, _, mainFrame, _ ->
                if (source !== view || !mainFrame || !isCurrent(view)) return@addWebMessageListener
                val raw = message.data ?: return@addWebMessageListener
                if (raw.length > MAX_MESSAGE_CHARS) return@addWebMessageListener
                val data = runCatching { JSONObject(raw) }.getOrNull() ?: return@addWebMessageListener
                if (data.optString("token") != token) return@addWebMessageListener
                val text = data.optString("text").trim()
                if (text.isEmpty() || text.length > MAX_TEXT_CHARS || text.any(Char::isISOControl)) {
                    return@addWebMessageListener
                }
                view.post { if (isCurrent(view)) onTranslate(text) }
            }
        }.isSuccess
        if (ok) bridges[view] = bridge
    }

    fun inject(view: WebView, label: String) {
        val bridge = bridges[view] ?: return
        if (!isCurrent(view)) return
        val script = """
            (function () {
              if (window.__pureSelectionChipInstalled) return;
              var root = document.documentElement;
              if (!root) return;
              var chip = document.createElement('button');
              chip.id = '__pureSelectionChip';
              chip.type = 'button';
              chip.textContent = ${JSONObject.quote(label)};
              chip.style.cssText = 'all:initial;position:fixed;z-index:2147483647;display:none;' +
                'box-sizing:border-box;border:0;border-radius:999px;padding:7px 11px;' +
                'background:#1F4E79;color:#fff;font:600 13px system-ui,-apple-system,sans-serif;' +
                'line-height:17px;box-shadow:0 3px 10px rgba(0,0,0,.26);cursor:pointer;' +
                'white-space:nowrap;';
              root.appendChild(chip);
              var hide = function () { chip.style.display = 'none'; };
              var place = function () {
                var selection = window.getSelection();
                if (!selection || selection.isCollapsed || selection.rangeCount < 1) { hide(); return; }
                var text = String(selection).trim();
                if (!text) { hide(); return; }
                var node = selection.anchorNode;
                var element = node && (node.nodeType === 1 ? node : node.parentElement);
                if (element && element.closest && element.closest(
                    'input,textarea,select,[contenteditable="true"],[contenteditable=""]')) {
                  hide(); return;
                }
                var rect = selection.getRangeAt(0).getBoundingClientRect();
                if (!rect || (!rect.width && !rect.height)) { hide(); return; }
                chip.style.display = 'block';
                var width = chip.offsetWidth || 90, height = chip.offsetHeight || 36;
                var left = Math.min(Math.max(8, rect.left + rect.width / 2 - width / 2),
                  Math.max(8, window.innerWidth - width - 8));
                // Android's own selection toolbar sits above the selection; keep the chip
                // below it so the two never cover each other.
                var top = rect.bottom + 8;
                if (top + height > window.innerHeight - 8) top = Math.max(8, rect.top - height - 8);
                chip.style.left = left + 'px';
                chip.style.top = Math.max(8, top) + 'px';
              };
              var timer = 0;
              var schedule = function () { clearTimeout(timer); timer = setTimeout(place, 80); };
              document.addEventListener('selectionchange', schedule, true);
              document.addEventListener('pointerup', schedule, true);
              document.addEventListener('keyup', schedule, true);
              document.addEventListener('scroll', hide, true);
              chip.addEventListener('mousedown', function (event) { event.preventDefault(); });
              chip.addEventListener('click', function (event) {
                event.preventDefault();
                var selection = window.getSelection();
                var text = selection ? String(selection).trim() : '';
                hide();
                if (!text) return;
                window[${JSONObject.quote(bridge.name)}].postMessage(JSON.stringify({
                  token: ${JSONObject.quote(bridge.token)}, text: text
                }));
              });
              window.__pureSelectionChipInstalled = true;
            })();
        """.trimIndent()
        runCatching { view.evaluateJavascript(script, null) }
    }

    fun remove(view: WebView) {
        val bridge = bridges.remove(view) ?: return
        runCatching { WebViewCompat.removeWebMessageListener(view, bridge.name) }
    }

    private companion object {
        const val MAX_MESSAGE_CHARS = 16 * 1024
        const val MAX_TEXT_CHARS = 10_000
    }
}
