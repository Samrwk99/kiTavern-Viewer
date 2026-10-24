package com.shinydiscoballsdev.kifossk

import android.content.Context
import android.graphics.Rect
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.WebView
import org.json.JSONArray

/**
 * Adds Find on page to Chromium's *existing* text-selection ActionMode.
 *
 * Delegates every native callback, including the floating toolbar's content
 * rectangle, so Copy/Paste/Select All and the native selection handles remain
 * entirely under WebView's control. Does not replace Chromium's toolbar.
 */
internal class FindOnPageWebView(context: Context) : WebView(context) {

    var onFindOnPageRequested: ((String) -> Unit)? = null

    private companion object {
        // App-owned ID: independent of Chromium's internal action IDs.
        const val FIND_ACTION_ID = 0x6b695f66
        const val GET_SELECTION_JS =
            "(function(){var s=window.getSelection();return s?s.toString():'';})()"
    }

    private fun wrap(callback: ActionMode.Callback): ActionMode.Callback =
        if (callback is FindActionModeCallback) callback else FindActionModeCallback(callback)

    override fun startActionMode(callback: ActionMode.Callback): ActionMode? =
        super.startActionMode(wrap(callback))

    override fun startActionMode(callback: ActionMode.Callback, type: Int): ActionMode? =
        super.startActionMode(wrap(callback), type)

    private inner class FindActionModeCallback(
        private val original: ActionMode.Callback
    ) : ActionMode.Callback2() {

        private fun addFindItem(menu: Menu): Boolean {
            if (menu.findItem(FIND_ACTION_ID) != null) return false
            menu.add(Menu.NONE, FIND_ACTION_ID, Menu.NONE, "Find on page")
                .setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            return true
        }

        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            if (!original.onCreateActionMode(mode, menu)) return false
            addFindItem(menu)
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
            val changed = original.onPrepareActionMode(mode, menu)
            // Some Chromium versions rebuild the menu during invalidation.
            return addFindItem(menu) || changed
        }

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            if (item.itemId != FIND_ACTION_ID) {
                return original.onActionItemClicked(mode, item)
            }

            // Capture before finishing ActionMode: closing Chromium's selection
            // toolbar can clear window.getSelection().
            evaluateJavascript(GET_SELECTION_JS) { encoded ->
                val selectedText = try {
                    JSONArray("[${encoded ?: "null"}]").optString(0, "")
                } catch (_: Exception) {
                    ""
                }
                mode.finish()
                onFindOnPageRequested?.invoke(selectedText)
            }
            return true
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            original.onDestroyActionMode(mode)
        }

        override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
            if (original is ActionMode.Callback2) {
                original.onGetContentRect(mode, view, outRect)
            } else {
                super.onGetContentRect(mode, view, outRect)
            }
        }
    }
}
