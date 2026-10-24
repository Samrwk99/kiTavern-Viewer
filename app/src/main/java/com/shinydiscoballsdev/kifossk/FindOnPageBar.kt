package com.shinydiscoballsdev.kifossk

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.webkit.WebView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** Lightweight browser-style find bar backed by WebView's own find/highlight APIs. */
internal class FindOnPageBar(private val activity: MainActivity) {
    private val handler = Handler(Looper.getMainLooper())
    private var currentWebView: WebView? = null
    private var bar: LinearLayout? = null
    private var pendingSearch: Runnable? = null
    private var matchesLabel: TextView? = null

    val isVisible: Boolean get() = bar != null

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density + 0.5f).toInt()

    fun show(webView: WebView, selectedText: String) {
        dismiss()
        currentWebView = webView
        val host = activity.findViewById<FrameLayout>(android.R.id.content)
        val colors = activity.obtainStyledAttributes(intArrayOf(android.R.attr.colorBackground))
        val surfaceColor = colors.getColor(0, 0xff303030.toInt())
        colors.recycle()

        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(4), dp(4))
            background = GradientDrawable().apply {
                setColor(surfaceColor)
                cornerRadius = dp(8).toFloat()
            }
            elevation = dp(6).toFloat()
        }
        bar = row

        val input = EditText(activity).apply {
            hint = "Find on page"
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(dp(6), 0, dp(4), 0)
        }
        row.addView(input, LinearLayout.LayoutParams(0, dp(44), 1f))

        val counter = TextView(activity).apply {
            text = "0/0"
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }
        matchesLabel = counter
        row.addView(counter, LinearLayout.LayoutParams(dp(48), dp(44)))

        fun actionButton(symbol: String, description: String, action: () -> Unit) {
            val button = TextView(activity).apply {
                text = symbol
                contentDescription = description
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                isClickable = true
                isFocusable = true
                val ripple = TypedValue()
                if (activity.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true)) {
                    setBackgroundResource(ripple.resourceId)
                }
                setOnClickListener { action() }
            }
            row.addView(button, LinearLayout.LayoutParams(dp(36), dp(44)))
        }
        actionButton("▲", "Previous match") { currentWebView?.findNext(false) }
        actionButton("▼", "Next match") { currentWebView?.findNext(true) }
        actionButton("✕", "Close find on page") { dismiss() }

        // This Activity renders edge-to-edge; don't place the find bar under
        // the Android status bar or a display cutout.
        val statusTop = ViewCompat.getRootWindowInsets(host)?.getInsets(
            WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout()
        )?.top ?: 0
        val layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP
        ).apply {
            leftMargin = dp(8)
            rightMargin = dp(8)
            topMargin = statusTop + dp(8)
        }
        host.addView(row, layoutParams)

        webView.setFindListener { activeIndex, matchCount, _ ->
            if (currentWebView === webView && isVisible) {
                matchesLabel?.text = if (matchCount == 0) "0/0"
                    else "${activeIndex.coerceAtLeast(0) + 1}/$matchCount"
            }
        }

        fun find(query: String) {
            if (currentWebView !== webView) return
            val text = query.trim()
            if (text.isEmpty()) {
                webView.clearMatches()
                matchesLabel?.text = "0/0"
            } else {
                matchesLabel?.text = "…"
                webView.findAllAsync(text)
            }
        }

        input.setText(selectedText.replace(Regex("\\s+"), " ").trim().take(256))
        input.setSelection(0, input.length())
        find(input.text.toString())
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                pendingSearch?.let { handler.removeCallbacks(it) }
                val query = s?.toString().orEmpty()
                val task = Runnable { find(query) }
                pendingSearch = task
                handler.postDelayed(task, 120L)
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                currentWebView?.findNext(true)
                true
            } else false
        }
        input.requestFocus()
        input.post {
            if (bar === row) {
                (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    /** Returns true if an active find session was closed (for Android Back). */
    fun dismiss(): Boolean {
        val oldBar = bar ?: return false
        bar = null
        pendingSearch?.let { handler.removeCallbacks(it) }
        pendingSearch = null
        matchesLabel = null
        (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(oldBar.windowToken, 0)
        (oldBar.parent as? FrameLayout)?.removeView(oldBar)
        currentWebView?.apply {
            setFindListener(null)
            clearMatches()
        }
        currentWebView = null
        return true
    }
}
