package com.shinydiscoballsdev.kifossk

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

/**
 * App-controlled Just once / Always browser picker for web links leaving SillyTavern.
 * The stored package is local to kiTavern: Android's system-wide browser default is
 * neither changed nor relied on, so "Ask every time" works even with a system default.
 */
internal object ExternalBrowserLauncher {
    private data class Browser(val packageName: String, val label: String)

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
    private fun installedBrowsers(context: Context): List<Browser> {
        val pm = context.packageManager
        val found = linkedMapOf<String, String>()
        for (scheme in listOf("https", "http")) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("$scheme://www.example.com/"))
                .addCategory(Intent.CATEGORY_BROWSABLE)
            val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
            } else {
                pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            }
            for (info in resolved) {
                val pkg = info.activityInfo?.packageName ?: continue
                if (pkg == context.packageName || pkg == "android") continue
                found.putIfAbsent(pkg, info.loadLabel(pm).toString().ifBlank { pkg })
            }
        }
        return found.map { (pkg, label) -> Browser(pkg, label) }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
    }

    fun selectedBrowserDescription(context: Context): String {
        val pkg = KioskPrefs.getExternalBrowserPackage(context) ?: return "Ask every time"
        return installedBrowsers(context).firstOrNull { it.packageName == pkg }?.label
            ?: "Unavailable browser (tap Change browser)"
    }

    fun open(activity: Activity, url: Uri) {
        if (url.scheme !in setOf("http", "https")) return
        val pkg = KioskPrefs.getExternalBrowserPackage(activity)
        if (pkg != null) {
            if (launch(activity, url, pkg)) return
            KioskPrefs.setExternalBrowserPackage(activity, null)
            Toast.makeText(activity, "Saved browser unavailable. Choose another browser.", Toast.LENGTH_LONG).show()
        }
        showPicker(activity, url)
    }

    private fun launch(activity: Activity, url: Uri, pkg: String): Boolean = try {
        activity.startActivity(Intent(Intent.ACTION_VIEW, url).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            setPackage(pkg)
        })
        true
    } catch (_: Exception) {
        false
    }

    private fun showPicker(activity: Activity, url: Uri) {
        val browsers = installedBrowsers(activity)
        if (browsers.isEmpty()) {
            // Extremely restricted Android builds may hide app discovery. Use the
            // system chooser in that case instead of silently swallowing the link.
            try {
                val intent = Intent(Intent.ACTION_VIEW, url)
                    .addCategory(Intent.CATEGORY_BROWSABLE)
                activity.startActivity(Intent.createChooser(intent, "Open link with"))
            } catch (_: Exception) {
                Toast.makeText(activity, "No browser available", Toast.LENGTH_LONG).show()
            }
            return
        }
        var selected = 0
        AlertDialog.Builder(activity)
            .setTitle("Open link with")
            .setSingleChoiceItems(browsers.map { it.label }.toTypedArray(), selected) { _, which ->
                selected = which
            }
            .setNegativeButton("Just once") { _, _ ->
                if (!launch(activity, url, browsers[selected].packageName)) {
                    Toast.makeText(activity, "Unable to open this browser", Toast.LENGTH_LONG).show()
                }
            }
            .setPositiveButton("Always") { _, _ ->
                val pkg = browsers[selected].packageName
                if (launch(activity, url, pkg)) {
                    KioskPrefs.setExternalBrowserPackage(activity, pkg)
                } else {
                    Toast.makeText(activity, "Unable to open this browser", Toast.LENGTH_LONG).show()
                }
            }
            .setNeutralButton("Cancel", null)
            .show()
    }

    fun chooseInSettings(activity: Activity, onChanged: () -> Unit) {
        val browsers = installedBrowsers(activity)
        if (browsers.isEmpty()) {
            Toast.makeText(activity, "No browsers found", Toast.LENGTH_LONG).show()
            return
        }
        val current = KioskPrefs.getExternalBrowserPackage(activity)
        var selected = browsers.indexOfFirst { it.packageName == current }.coerceAtLeast(0)
        AlertDialog.Builder(activity)
            .setTitle("External links browser")
            .setSingleChoiceItems(browsers.map { it.label }.toTypedArray(), selected) { _, which ->
                selected = which
            }
            .setPositiveButton("Use selected browser") { _, _ ->
                KioskPrefs.setExternalBrowserPackage(activity, browsers[selected].packageName)
                onChanged()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
