package com.shinydiscoballsdev.kifossk

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import android.widget.ArrayAdapter
import android.widget.CheckedTextView
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog

/**
 * kiTavern's external-link picker. Unlike Intent.createChooser(), this supports
 * a per-app "Always" choice without modifying Android's browser defaults.
 *
 * A dedicated stored value means "follow Android's CURRENT default browser",
 * even if the user changes that default later in Android settings.
 */
internal object ExternalBrowserLauncher {
    private const val SYSTEM_DEFAULT = "__kitavern_system_default_browser__"
    private data class AppChoice(val packageName: String, val label: String, val isBrowser: Boolean)
    private data class LinkChoice(val packageName: String?, val label: String)

    fun isExternalWebLink(context: Context, target: Uri): Boolean {
        if (target.scheme !in setOf("http", "https") || target.host.isNullOrBlank()) return false
        val home = try { Uri.parse(KioskPrefs.getUrl(context)) } catch (_: Exception) { return true }
        fun effectivePort(uri: Uri): Int = when {
            uri.port >= 0 -> uri.port
            uri.scheme.equals("https", true) -> 443
            else -> 80
        }
        return !(
            target.scheme.equals(home.scheme, ignoreCase = true) &&
                target.host.equals(home.host, ignoreCase = true) &&
                effectivePort(target) == effectivePort(home)
            )
    }

    @Suppress("DEPRECATION")
    private fun query(context: Context, intent: Intent): List<android.content.pm.ResolveInfo> {
        val pm = context.packageManager
        // Do NOT use MATCH_DEFAULT_ONLY: it can omit browsers that are not the default.
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            pm.queryIntentActivities(intent, 0)
        }
    }

    private fun putApp(
        context: Context,
        found: MutableMap<String, AppChoice>,
        info: android.content.pm.ResolveInfo,
        browser: Boolean
    ) {
        val pkg = info.activityInfo?.packageName ?: return
        if (pkg == context.packageName || pkg == "android" || pkg.isBlank()) return
        val previous = found[pkg]
        if (previous != null) {
            if (browser && !previous.isBrowser) {
                found[pkg] = previous.copy(isBrowser = true)
            }
            return
        }
        val label = try { info.loadLabel(context.packageManager).toString().ifBlank { pkg } }
        catch (_: Exception) { pkg }
        found[pkg] = AppChoice(pkg, label, browser)
    }

    private fun systemDefaultPackage(context: Context): String? {
        // RoleManager.getRoleHolders() is a hidden system API requiring a
        // privileged permission. Ordinary apps must use public intent resolution
        // to determine the current default handler for ordinary web links.
        val intent = webIntent(Uri.parse("https://www.example.com/"))
        return try {
            @Suppress("DEPRECATION")
            val resolved = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            resolved?.activityInfo?.packageName?.takeUnless {
                it == "android" || it == context.packageName || it.isNullOrBlank()
            }
        } catch (_: Exception) { null }
    }

    private fun webIntent(url: Uri): Intent = Intent(Intent.ACTION_VIEW, url).apply {
        addCategory(Intent.CATEGORY_BROWSABLE)
    }

    private fun looksLikeBrowser(app: AppChoice): Boolean {
        val s = "${app.label} ${app.packageName}".lowercase()
        // Supplement the standard browser-app and URL intent queries: some
        // browser launchers do not advertise CATEGORY_APP_BROWSER.
        return listOf(
            "browser", "firefox", "chrome", "chromium", "brave", "vivaldi",
            "opera", "kiwi", "duckduckgo", "samsung internet", "edge", "tor browser",
            "waterfox", "fennec", "iceraven", "cromite", "mull", "yandex",
            "via browser", "soul browser", "vivaldi", "arc search"
        ).any { s.contains(it) }
    }

    private fun availableApps(context: Context): List<AppChoice> {
        val found = linkedMapOf<String, AppChoice>()
        val defaultPkg = systemDefaultPackage(context)

        // Explicit browser launcher category, which does not depend on the
        // system's current default URL resolver.
        val browserLaunch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_BROWSER)
        query(context, browserLaunch).forEach { putApp(context, found, it, browser = true) }

        // Browser URL handlers, queried without MATCH_DEFAULT_ONLY.
        for (scheme in listOf("https", "http")) {
            for (host in listOf("www.example.com", "www.wikipedia.org")) {
                val intent = webIntent(Uri.parse("$scheme://$host/"))
                query(context, intent).forEach { putApp(context, found, it, browser = true) }
            }
        }

        // Settings deliberately allows any user-launchable app, even apps that
        // aren't browsers. The manifest declares MAIN/LAUNCHER visibility.
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        query(context, launcher).forEach { putApp(context, found, it, browser = false) }

        // In the unlikely event the browser role holder doesn't expose a
        // matching query result, still offer its package as a browser.
        if (!defaultPkg.isNullOrBlank() && !found.containsKey(defaultPkg)) {
            try {
                val pm = context.packageManager
                @Suppress("DEPRECATION")
                val info = pm.getApplicationInfo(defaultPkg, 0)
                found[defaultPkg] = AppChoice(defaultPkg, pm.getApplicationLabel(info).toString(), true)
            } catch (_: Exception) { /* Default browser remains available as a generic option. */ }
        }

        return found.values.map { app ->
            if (app.packageName == defaultPkg || looksLikeBrowser(app)) app.copy(isBrowser = true)
            else app
        }.sortedWith(compareBy<AppChoice>({ !it.isBrowser }, { it.label.lowercase() }))
    }

    private fun browserChoices(
        context: Context, allApps: List<AppChoice> = availableApps(context)
    ): List<LinkChoice> {
        val apps = allApps.filter { it.isBrowser }
        val defaultPackage = systemDefaultPackage(context)
        val defaultName = apps.firstOrNull { it.packageName == defaultPackage }?.label
        val defaultLabel = if (defaultName != null) "Default browser ($defaultName)" else "Default browser (Android)"
        return listOf(LinkChoice(SYSTEM_DEFAULT, defaultLabel)) +
            apps.filter { it.packageName != defaultPackage }.map { LinkChoice(it.packageName, it.label) }
    }

    /**
     * Native single-choice rows with each installed app's real launcher icon.
     * The default-browser row shows the icon of Android's current default.
     * This changes presentation only; it does not change candidate discovery,
     * intent routing, saved choices, or Just once / Always behavior.
     */
    private fun iconChoicesAdapter(context: Context, choices: List<LinkChoice>): ArrayAdapter<String> {
        val labels = choices.map { it.label }
        val iconPx = (24f * context.resources.displayMetrics.density + 0.5f).toInt()
        val iconGapPx = (12f * context.resources.displayMetrics.density + 0.5f).toInt()
        val pm = context.packageManager
        val defaultPackage = systemDefaultPackage(context)
        // Cache package icon lookups only for the lifetime of this dialog.
        val iconCache = HashMap<String, android.graphics.drawable.Drawable?>()
        val fallbackIcon = context.getDrawable(android.R.drawable.sym_def_app_icon)
        return object : ArrayAdapter<String>(context, android.R.layout.select_dialog_singlechoice, labels) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = super.getView(position, convertView, parent)
                val text = row as? CheckedTextView ?: return row
                val chosenPackage = choices[position].packageName
                val iconPackage = if (chosenPackage == SYSTEM_DEFAULT) defaultPackage else chosenPackage
                val drawable = if (iconPackage.isNullOrBlank()) {
                    fallbackIcon
                } else {
                    if (iconCache.containsKey(iconPackage)) {
                        iconCache[iconPackage]
                    } else {
                        val icon = try { pm.getApplicationIcon(iconPackage) }
                            catch (_: Exception) { fallbackIcon }
                        iconCache[iconPackage] = icon
                        icon
                    }
                }
                drawable?.setBounds(0, 0, iconPx, iconPx)
                text.setCompoundDrawablesRelative(drawable, null, null, null)
                text.compoundDrawablePadding = iconGapPx
                return row
            }
        }
    }

    fun selectedBrowserDescription(context: Context): String {
        val pkg = KioskPrefs.getExternalBrowserPackage(context) ?: return "Ask every time"
        if (pkg == SYSTEM_DEFAULT) return browserChoices(context).first().label
        return availableApps(context).firstOrNull { it.packageName == pkg }?.label
            ?: "Unavailable app (tap Change app)"
    }

    fun open(activity: Activity, url: Uri) {
        if (url.scheme !in setOf("http", "https")) return
        val pkg = KioskPrefs.getExternalBrowserPackage(activity)
        if (pkg != null) {
            if (launch(activity, url, pkg)) return
            KioskPrefs.setExternalBrowserPackage(activity, null)
            Toast.makeText(activity, "Saved app cannot open web links. Choose another.", Toast.LENGTH_LONG).show()
        }
        showPicker(activity, url)
    }

    private fun launch(activity: Activity, url: Uri, pkg: String): Boolean {
        if (pkg == SYSTEM_DEFAULT) {
            val actualDefault = systemDefaultPackage(activity)
            if (!actualDefault.isNullOrBlank() && launch(activity, url, actualDefault)) return true
            // On older Android or with no role holder: let the system choose.
            return try {
                activity.startActivity(webIntent(url))
                true
            } catch (_: Exception) { false }
        }
        return try {
            activity.startActivity(webIntent(url).apply { setPackage(pkg) })
            true
        } catch (_: Exception) { false }
    }

    private fun showPicker(activity: Activity, url: Uri) {
        val choices = browserChoices(activity)
        var selected = 0 // Default browser is the initial selection.
        AlertDialog.Builder(activity)
            .setTitle("Open link with")
            .setSingleChoiceItems(iconChoicesAdapter(activity, choices), selected) { _, which ->
                selected = which
            }
            .setNegativeButton("Just once") { _, _ ->
                if (!launch(activity, url, choices[selected].packageName!!)) {
                    Toast.makeText(activity, "Unable to open selected browser", Toast.LENGTH_LONG).show()
                }
            }
            .setPositiveButton("Always") { _, _ ->
                val pkg = choices[selected].packageName!!
                if (launch(activity, url, pkg)) {
                    KioskPrefs.setExternalBrowserPackage(activity, pkg)
                } else {
                    Toast.makeText(activity, "Unable to open selected browser", Toast.LENGTH_LONG).show()
                }
            }
            .setNeutralButton("Cancel", null)
            .show()
    }

    fun chooseInSettings(activity: Activity, onChanged: () -> Unit) {
        val choices = availableApps(activity)
        val defaultLabel = browserChoices(activity, choices).first().label
        // All user-launchable apps are exposed in Settings. Apps that aren't
        // web handlers may be selected; on opening a link we fail safely back
        // to the picker rather than losing the link or navigating in WebView.
        val allChoices = listOf(LinkChoice(SYSTEM_DEFAULT, defaultLabel)) +
            choices.map { app ->
                val prefix = if (app.isBrowser) "" else "Other app: "
                LinkChoice(app.packageName, "$prefix${app.label}")
            }
        val current = KioskPrefs.getExternalBrowserPackage(activity)
        var selected = allChoices.indexOfFirst { it.packageName == current }.coerceAtLeast(0)
        AlertDialog.Builder(activity)
            .setTitle("External links app")
            .setSingleChoiceItems(iconChoicesAdapter(activity, allChoices), selected) { _, which ->
                selected = which
            }
            .setPositiveButton("Use selected app") { _, _ ->
                val choice = allChoices[selected]
                if (choice.label.startsWith("Other app: ")) {
                    AlertDialog.Builder(activity)
                        .setTitle("App may not open web links")
                        .setMessage("This app may not support opening web pages. If it cannot open a link, kiTavern will ask you to choose again.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Use anyway") { _, _ ->
                            KioskPrefs.setExternalBrowserPackage(activity, choice.packageName)
                            onChanged()
                        }.show()
                } else {
                    KioskPrefs.setExternalBrowserPackage(activity, choice.packageName)
                    onChanged()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
