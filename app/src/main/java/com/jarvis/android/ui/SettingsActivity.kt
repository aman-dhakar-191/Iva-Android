package com.jarvis.android.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.getSystemService
import com.jarvis.android.R
import com.jarvis.android.Settings
import com.jarvis.android.databinding.ActivitySettingsBinding
import com.jarvis.android.service.JarvisSessionService

/**
 * Everything the shell needs configured: which gateway to load, and whether the
 * session should survive backgrounding. First run lands here automatically when
 * no gateway URL was baked in at build time.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var settings: Settings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        settings = Settings(this)

        binding.urlInput.setText(settings.gatewayUrl)
        binding.keepAliveSwitch.isChecked = settings.keepSessionAlive

        binding.saveButton.setOnClickListener { save() }
        binding.batteryButton.setOnClickListener { requestBatteryExemption() }
        binding.batteryButton.visibility =
            if (isIgnoringBatteryOptimizations()) View.GONE else View.VISIBLE
    }

    private fun save() {
        val raw = binding.urlInput.text?.toString().orEmpty()
        if (!Settings.isValid(raw)) {
            binding.urlLayout.error = getString(R.string.invalid_url)
            return
        }
        binding.urlLayout.error = null
        settings.gatewayUrl = raw
        settings.keepSessionAlive = binding.keepAliveSwitch.isChecked
        if (!binding.keepAliveSwitch.isChecked) JarvisSessionService.stop(this)

        Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
        // Relaunch the shell so the WebView loads the new origin from scratch
        // rather than navigating across origins with the old page's state.
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        finish()
    }

    private fun isIgnoringBatteryOptimizations(): Boolean =
        getSystemService<PowerManager>()?.isIgnoringBatteryOptimizations(packageName) ?: true

    /**
     * A long-lived voice session is exactly the case Doze is allowed to
     * interrupt, so offer the exemption — but only ever by sending the user to
     * the system dialog, never by asserting it silently.
     */
    @SuppressLint("BatteryLife")
    private fun requestBatteryExemption() {
        if (isIgnoringBatteryOptimizations()) return
        val intent = Intent(
            AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:$packageName")
        )
        try {
            startActivity(intent)
        } catch (_: Exception) {
            startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }
}
