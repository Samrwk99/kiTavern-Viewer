package com.shinydiscoballsdev.kifossk

import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URLEncoder
import kotlin.math.max
import kotlin.math.min

/** Android floating toolbar for custom webpage and editable-field selection. */
internal class SelectionToolbar(private val activity: MainActivity) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var page: WebView? = null
    private val findOnPage = FindOnPageBar(activity)
    private var toolbarHost: View? = null
    private var floatingMode: ActionMode? = null
    private var editableSelection = false
    private val selectionRect = Rect()
    private val processTargets = mutableMapOf<Int, Intent>()

    private companion object {
        const val COPY = 1
        const val SHARE = 2
        const val SELECT_ALL = 3
        const val SEARCH = 4
        const val FIND_ON_PAGE = 5
        const val CUT = 6
        const val PASTE = 7
        const val PROCESS_FIRST = 100
        const val INTERFACE_NAME = "KiTavernSelectionToolbar"
    }

    /** Rebinds the Javascript bridge after an Activity recreation with a retained WebView. */
    fun attach(view: WebView) {
        page = view
        // Host the contextual toolbar in the Activity, not the WebView. Using
        // WebView.startActionMode can interact with Chromium's own selection UI.
        toolbarHost = activity.findViewById(android.R.id.content)
        view.removeJavascriptInterface(INTERFACE_NAME)
        view.addJavascriptInterface(Bridge(), INTERFACE_NAME)
    }

    private inner class Bridge {
        @JavascriptInterface
        fun show(left: Double, top: Double, right: Double, bottom: Double,
                 viewportWidth: Double, viewportHeight: Double, editable: Boolean) {
            mainHandler.post {
                if (findOnPage.isVisible) return@post
                val view = page ?: return@post
                if (!view.isAttachedToWindow || view.width <= 0 || view.height <= 0 ||
                    viewportWidth <= 0 || viewportHeight <= 0 ||
                    !UrlValidator.isValid(view.url)) return@post

                val scaleX = view.width / viewportWidth
                val scaleY = view.height / viewportHeight
                val x1 = (left * scaleX).toInt().coerceIn(0, view.width)
                val y1 = (top * scaleY).toInt().coerceIn(0, view.height)
                val x2 = (right * scaleX).toInt().coerceIn(0, view.width)
                val y2 = (bottom * scaleY).toInt().coerceIn(0, view.height)
                if (x1 >= x2 || y1 >= y2) return@post
                val host = toolbarHost ?: return@post
                val viewLocation = IntArray(2)
                val hostLocation = IntArray(2)
                view.getLocationOnScreen(viewLocation)
                host.getLocationOnScreen(hostLocation)
                val dx = viewLocation[0] - hostLocation[0]
                val dy = viewLocation[1] - hostLocation[1]
                // The ActionMode menu is created once per selection type; a
                // transition to/from an editable field needs new menu actions.
                if (editableSelection != editable && floatingMode != null) finishToolbar()
                editableSelection = editable
                val next = Rect(x1 + dx, y1 + dy, x2 + dx, y2 + dy)
                if (next != selectionRect) {
                    selectionRect.set(next)
                    floatingMode?.invalidateContentRect()
                }
                if (floatingMode == null) {
                    floatingMode = host.startActionMode(toolbarCallback, ActionMode.TYPE_FLOATING)
                }
            }
        }

        @JavascriptInterface
        fun hide() {
            mainHandler.post { finishToolbar() }
        }
    }

    private val toolbarCallback = object : ActionMode.Callback2() {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            // Native Android decides which actions fit, and places the rest in its overflow menu.
            if (editableSelection) {
                menu.add(Menu.NONE, CUT, 0, "Cut").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
                menu.add(Menu.NONE, PASTE, 1, "Paste").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            }
            menu.add(Menu.NONE, COPY, 2, "Copy").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            menu.add(Menu.NONE, SHARE, 1, "Share").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            menu.add(Menu.NONE, SELECT_ALL, 2, "Select all").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            menu.add(Menu.NONE, SEARCH, 3, "Web search").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            menu.add(Menu.NONE, FIND_ON_PAGE, 4, "Find on page")
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)

            // The same ACTION_PROCESS_TEXT contract used by Android's normal text tools.
            processTargets.clear()
            val request = Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain")
            val pm = activity.packageManager
            pm.queryIntentActivities(request, 0).forEachIndexed { index, info ->
                val component = info.activityInfo ?: return@forEachIndexed
                if (!component.exported) return@forEachIndexed
                val id = PROCESS_FIRST + index
                val intent = Intent(Intent.ACTION_PROCESS_TEXT)
                    .setType("text/plain")
                    .setClassName(component.packageName, component.name)
                processTargets[id] = intent
                menu.add(Menu.NONE, id, 100 + index, info.loadLabel(pm))
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
            }
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false

        override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
            outRect.set(selectionRect)
        }

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            when (item.itemId) {
                SELECT_ALL -> {
                    page?.evaluateJavascript(
                        "window.__kiTavernChatSelect && window.__kiTavernChatSelect.selectAll();", null
                    )
                    return true
                }
                FIND_ON_PAGE -> withSelectedText { text ->
                    // Close our custom text selection before starting a normal
                    // WebView find session; otherwise its handles overlay matches.
                    dismissSelection()
                    page?.let { findOnPage.show(it, text) }
                }
                CUT -> withSelectedText { text ->
                    if (editableSelection) {
                        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Selected text", text))
                        replaceEditableText("")
                    }
                }
                PASTE -> {
                    if (editableSelection) {
                        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = clipboard.primaryClip
                        val text = clip?.getItemAt(0)?.coerceToText(activity)?.toString()
                        if (text != null) replaceEditableText(text)
                    }
                }
                COPY, SHARE, SEARCH -> withSelectedText { text ->
                    if (text.isNotEmpty()) when (item.itemId) {
                        COPY -> {
                            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Selected text", text))
                        }
                        SHARE -> {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, text)
                            }
                            activity.startActivity(Intent.createChooser(send, null))
                        }
                        SEARCH -> searchWeb(text)
                    }
                    dismissSelection()
                }
                else -> {
                    val target = processTargets[item.itemId] ?: return false
                    withSelectedText { text ->
                        if (text.isNotEmpty()) try {
                            activity.startActivity(Intent(target).apply {
                                putExtra(Intent.EXTRA_PROCESS_TEXT, text)
                                putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
                            })
                        } catch (_: ActivityNotFoundException) { /* Uninstalled since menu opened. */ }
                        dismissSelection()
                    }
                }
            }
            return true
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            if (floatingMode === mode) floatingMode = null
            // Do not clear the selected text: a native action-mode teardown can happen
            // during scrolling, and the user explicitly wants the selection to persist.
        }
    }

    private fun withSelectedText(callback: (String) -> Unit) {
        val view = page ?: return
        view.evaluateJavascript(
            "window.__kiTavernChatSelect ? window.__kiTavernChatSelect.selectedText() : '';"
        ) { result ->
            val text = try { JSONTokener(result).nextValue() as? String ?: "" }
            catch (_: Exception) { "" }
            callback(text)
        }
    }

    private fun replaceEditableText(text: String) {
        // Proper JSON encoding prevents quotes, newlines and Unicode content
        // from being interpreted as JavaScript when pasting clipboard text.
        page?.evaluateJavascript(
            "window.__kiTavernChatSelect && " +
                "window.__kiTavernChatSelect.replaceSelection(${JSONObject.quote(text)});", null
        )
        // replaceSelection() closes the custom handles itself, and the native
        // menu finishes without moving focus out of the real input element.
        finishToolbar()
    }

    private fun searchWeb(text: String) {
        val intent = Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, text)
        try {
            activity.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            // Devices without a search handler can use their ordinary default browser.
            val url = "https://www.google.com/search?q=" + URLEncoder.encode(text, "UTF-8")
            try { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            catch (_: ActivityNotFoundException) { /* No browser installed. */ }
        }
    }

    private fun dismissSelection() {
        finishToolbar()
        page?.evaluateJavascript(
            "window.__kiTavernChatSelect && window.__kiTavernChatSelect.dismiss();", null
        )
    }

    private fun finishToolbar() {
        val previous = floatingMode
        floatingMode = null
        previous?.finish()
    }

    fun dismissOnBack(): Boolean {
        if (findOnPage.dismiss()) return true
        if (floatingMode == null) return false
        dismissSelection()
        return true
    }

    fun detach() {
        findOnPage.dismiss()
        finishToolbar()
        page = null
        toolbarHost = null
    }
}
