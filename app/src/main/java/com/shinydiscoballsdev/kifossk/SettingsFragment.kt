package com.shinydiscoballsdev.kifossk

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.SwitchCompat
import androidx.fragment.app.Fragment

class SettingsFragment : Fragment() {

    private lateinit var editTextUrl: EditText
    private lateinit var switchScreenOn: SwitchCompat
    private lateinit var switchKeepAlive: SwitchCompat
    private lateinit var switchShowNotification: SwitchCompat
    private lateinit var switchAutoFocusChatInput: SwitchCompat
    private lateinit var spinnerOrientation: Spinner
    private lateinit var spinnerTheme: Spinner
    private lateinit var btnSave: Button
    private lateinit var btnReload: Button
    private lateinit var textExternalBrowserStatus: TextView
    private lateinit var btnChooseExternalBrowser: Button
    private lateinit var btnResetExternalBrowser: Button
    private lateinit var textBatteryStatus: TextView
    private lateinit var btnBatteryExemption: Button
    private lateinit var btnBatterySettings: Button

    private var startX = 0f
    private var startY = 0f
    private var isDragging = false
    private val swipeThreshold by lazy { resources.displayMetrics.widthPixels * 0.25f }

    var onDismiss: (() -> Unit)? = null
    var onReload: (() -> Unit)? = null
    var onSettingsChanged: (() -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        editTextUrl = view.findViewById(R.id.editTextUrl)
        switchScreenOn = view.findViewById(R.id.switchScreenOn)
        switchKeepAlive = view.findViewById(R.id.switchKeepAlive)
        switchShowNotification = view.findViewById(R.id.switchShowNotification)
        switchAutoFocusChatInput = view.findViewById(R.id.switchAutoFocusChatInput)
        spinnerOrientation = view.findViewById(R.id.spinnerOrientation)
        spinnerTheme = view.findViewById(R.id.spinnerTheme)
        btnSave = view.findViewById(R.id.buttonSave)
        btnReload = view.findViewById(R.id.buttonReload)
        textExternalBrowserStatus = view.findViewById(R.id.textExternalBrowserStatus)
        btnChooseExternalBrowser = view.findViewById(R.id.buttonChooseExternalBrowser)
        btnResetExternalBrowser = view.findViewById(R.id.buttonResetExternalBrowser)
        textBatteryStatus = view.findViewById(R.id.textBatteryStatus)
        btnBatteryExemption = view.findViewById(R.id.buttonBatteryExemption)
        btnBatterySettings = view.findViewById(R.id.buttonBatterySettings)

        val ctx = requireContext()
        editTextUrl.setText(KioskPrefs.getUrl(ctx))
        switchScreenOn.isChecked = KioskPrefs.getScreenOn(ctx)
        switchKeepAlive.isChecked = KioskPrefs.getKeepAlive(ctx)
        switchShowNotification.isChecked = KioskPrefs.getShowNotification(ctx)
        switchAutoFocusChatInput.isChecked = KioskPrefs.getAutoFocusChatInput(ctx)

        switchKeepAlive.setOnCheckedChangeListener { _, enabled ->
            KioskPrefs.setKeepAlive(ctx, enabled)
            if (enabled) {
                KeepAliveService.start(ctx)
            } else {
                // This is the setting-controlled stop path. MainActivity.onDestroy never stops it.
                KeepAliveService.stop(ctx)
            }
        }

        switchShowNotification.setOnCheckedChangeListener { _, showDetails ->
            KioskPrefs.setShowNotification(ctx, showDetails)
            // Restarting a running service is not required; start() sends it an update command.
            // The FGS notification itself remains required by Android.
            if (KioskPrefs.getKeepAlive(ctx)) KeepAliveService.start(ctx)
        }

        ArrayAdapter.createFromResource(
            ctx,
            R.array.orientation_options,
            android.R.layout.simple_spinner_item
        ).also { adapter ->
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinnerOrientation.adapter = adapter
            val map = mapOf("landscape" to 0, "portrait" to 1, "auto" to 2)
            spinnerOrientation.setSelection(map[KioskPrefs.getOrientation(ctx)] ?: 2)
        }

        ArrayAdapter.createFromResource(
            ctx,
            R.array.theme_options,
            android.R.layout.simple_spinner_item
        ).also { adapter ->
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinnerTheme.adapter = adapter
            val map = mapOf("dark" to 0, "light" to 1)
            spinnerTheme.setSelection(map[KioskPrefs.getTheme(ctx)] ?: 0)
        }

        spinnerTheme.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?,
                selectedView: View?,
                position: Int,
                id: Long
            ) {
                val selectedTheme = mapOf(0 to "dark", 1 to "light")[position] ?: "dark"
                if (selectedTheme != KioskPrefs.getTheme(ctx)) {
                    // Save every field before Activity recreation; WebView state is restored by MainActivity.
                    saveSettings(notifyActivity = false)
                    // Full native theme resources still require Activity recreation.
                    // MainActivity retains and rebinds the live WebView across it.
                    activity?.recreate()
                }
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }

        btnSave.setOnClickListener {
            saveSettings()
            Toast.makeText(ctx, "Settings saved!", Toast.LENGTH_SHORT).show()
        }

        btnReload.setOnClickListener {
            saveSettings()
            onReload?.invoke()
            dismiss()
        }

        fun updateExternalBrowserPreference() {
            textExternalBrowserStatus.text =
                "Current: ${ExternalBrowserLauncher.selectedBrowserDescription(ctx)}"
            btnResetExternalBrowser.isEnabled = KioskPrefs.getExternalBrowserPackage(ctx) != null
        }
        btnChooseExternalBrowser.setOnClickListener {
            activity?.let { ExternalBrowserLauncher.chooseInSettings(it) { updateExternalBrowserPreference() } }
        }
        btnResetExternalBrowser.setOnClickListener {
            KioskPrefs.setExternalBrowserPackage(ctx, null)
            updateExternalBrowserPreference()
        }
        updateExternalBrowserPreference()

        btnBatteryExemption.setOnClickListener { requestBatteryExemption(ctx) }
        btnBatterySettings.setOnClickListener { openAppBatterySettings(ctx) }

        view.setBackgroundColor(
            if (KioskPrefs.getTheme(ctx) == "light") 0xFFF5F5F5.toInt() else 0xFF1A1A2E.toInt()
        )
        updateBatteryStatus(ctx)
    }

    fun onBackPressed(): Boolean {
        saveSettings()
        dismiss()
        return true
    }

    fun handleTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.rawX
                startY = event.rawY
                isDragging = false
                view?.animate()?.cancel()
            }

            MotionEvent.ACTION_MOVE -> {
                val deltaX = event.rawX - startX
                val deltaY = event.rawY - startY
                if (!isDragging && kotlin.math.abs(deltaX) > kotlin.math.abs(deltaY) && kotlin.math.abs(deltaX) > 20) {
                    isDragging = true
                }
                if (isDragging) {
                    view?.translationX = deltaX
                    return true
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDragging) {
                    val deltaX = event.rawX - startX
                    val fragmentView = view ?: return false
                    if (kotlin.math.abs(deltaX) > swipeThreshold) {
                        val endX = if (deltaX > 0) fragmentView.width.toFloat() else -fragmentView.width.toFloat()
                        fragmentView.animate()
                            .translationX(endX)
                            .setDuration(200)
                            .setInterpolator(DecelerateInterpolator())
                            .withEndAction {
                                saveSettings()
                                dismiss()
                            }
                            .start()
                    } else {
                        fragmentView.animate()
                            .translationX(0f)
                            .setDuration(150)
                            .setInterpolator(DecelerateInterpolator())
                            .start()
                    }
                    isDragging = false
                    return true
                }
            }
        }
        return false
    }

    private fun requestBatteryExemption(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            Toast.makeText(context, "Android battery optimisation exemption is not applicable on this Android version.", Toast.LENGTH_LONG).show()
            return
        }

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (powerManager.isIgnoringBatteryOptimizations(context.packageName)) {
            Toast.makeText(context, "Android battery optimisation is already unrestricted for this app.", Toast.LENGTH_LONG).show()
            updateBatteryStatus(context)
            return
        }

        // This system prompt is deliberately user-triggered from Settings rather than shown at launch.
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
            )
        } catch (e: Exception) {
            // Some ROMs omit/disable the direct-request screen. Fall back to Android's list.
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
                openAppBatterySettings(context)
            }
        }
    }

    private fun openAppBatterySettings(context: Context) {
        try {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
            )
        } catch (_: Exception) {
            Toast.makeText(context, "Open this app's system settings and allow unrestricted background use.", Toast.LENGTH_LONG).show()
        }
    }

    private fun updateBatteryStatus(context: Context) {
        if (!::textBatteryStatus.isInitialized) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            textBatteryStatus.text = "Android Doze battery optimisation does not apply on this Android version."
            return
        }

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        textBatteryStatus.text = if (powerManager.isIgnoringBatteryOptimizations(context.packageName)) {
            "Android battery optimisation: unrestricted. Manufacturer-specific background limits may still apply."
        } else {
            "Android battery optimisation: enabled. You can request an exemption below. Some manufacturers add separate background/autostart controls."
        }
    }

    private fun dismiss() {
        onDismiss?.invoke()
    }

    private fun saveSettings(notifyActivity: Boolean = true) {
        val ctx = context ?: return
        val previousKeepAlive = KioskPrefs.getKeepAlive(ctx)
        val desiredKeepAlive = switchKeepAlive.isChecked
        val orientationMap = mapOf(0 to "landscape", 1 to "portrait", 2 to "auto")
        val themeMap = mapOf(0 to "dark", 1 to "light")
        KioskPrefs.saveSettings(
            ctx,
            editTextUrl.text.toString(),
            switchScreenOn.isChecked,
            desiredKeepAlive,
            switchShowNotification.isChecked,
            switchAutoFocusChatInput.isChecked,
            orientationMap[spinnerOrientation.selectedItemPosition] ?: "auto",
            themeMap[spinnerTheme.selectedItemPosition] ?: "dark"
        )
        // The switch still takes effect immediately. Saving the other fields
        // must not cause an unnecessary restart of the service.
        if (desiredKeepAlive != previousKeepAlive) {
            if (desiredKeepAlive) KeepAliveService.start(ctx) else KeepAliveService.stop(ctx)
        }

        if (notifyActivity) onSettingsChanged?.invoke()
        updateBatteryStatus(ctx)
    }

    override fun onResume() {
        super.onResume()
        context?.let(::updateBatteryStatus)
    }

    override fun onPause() {
        saveSettings()
        super.onPause()
    }
}
