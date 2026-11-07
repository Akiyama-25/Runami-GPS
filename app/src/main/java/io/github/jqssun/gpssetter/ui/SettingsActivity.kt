package io.github.jqssun.gpssetter.ui

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.text.method.DigitsKeyListener
import android.view.MenuItem
import android.widget.EditText
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.AppCompatButton
import androidx.lifecycle.lifecycleScope
import androidx.preference.DropDownPreference
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceDataStore
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import dagger.hilt.android.AndroidEntryPoint
import io.github.jqssun.gpssetter.BuildConfig
import io.github.jqssun.gpssetter.R
import io.github.jqssun.gpssetter.databinding.ActivitySettingsBinding
import io.github.jqssun.gpssetter.ui.viewmodel.MainViewModel
import io.github.jqssun.gpssetter.update.UpdateChecker
import io.github.jqssun.gpssetter.utils.AppLogger
import io.github.jqssun.gpssetter.utils.JoystickService
import io.github.jqssun.gpssetter.utils.LocaleHelper
import io.github.jqssun.gpssetter.utils.PrefManager
import io.github.jqssun.gpssetter.utils.ext.showToast
import kotlinx.coroutines.launch

@AndroidEntryPoint
class ActivitySettings : AppCompatActivity() {

    private val binding by lazy {
        ActivitySettingsBinding.inflate(layoutInflater)
    }

    private val viewModel by viewModels<MainViewModel>()
    private var updateDialogInstance: AlertDialog? = null

    class SettingPreferenceDataStore : PreferenceDataStore() {
        override fun getBoolean(key: String?, defValue: Boolean): Boolean {
            return when (key) {
                "system_hooked" -> PrefManager.isSystemHooked
                "random_position" -> PrefManager.isRandomPosition
                "update_disabled" -> PrefManager.isUpdateDisabled
                "joystick_enabled" -> PrefManager.isJoystickEnabled
                else -> throw IllegalArgumentException("Invalid key $key")
            }
        }

        override fun putBoolean(key: String?, value: Boolean) {
            when (key) {
                "system_hooked" -> PrefManager.isSystemHooked = value
                "random_position" -> PrefManager.isRandomPosition = value
                "update_disabled" -> PrefManager.isUpdateDisabled = value
                "joystick_enabled" -> PrefManager.isJoystickEnabled = value
                else -> throw IllegalArgumentException("Invalid key $key")
            }
            PrefManager.makeWorldReadable()
            AppLogger.logApp(
                action = "Settings.putBoolean ($key)",
                details = "配置项 $key 已更新为 $value"
            )
        }

        override fun getString(key: String?, defValue: String?): String? {
            return when (key) {
                "accuracy_level" -> PrefManager.accuracy
                "map_type" -> PrefManager.mapType.toString()
                "dark_theme" -> PrefManager.darkTheme.toString()
                "app_language" -> PrefManager.appLanguage
                "update_channel" -> PrefManager.updateChannel
                else -> throw IllegalArgumentException("Invalid key $key")
            }
        }

        override fun putString(key: String?, value: String?) {
            when (key) {
                "accuracy_level" -> PrefManager.accuracy = value
                "map_type" -> PrefManager.mapType = value!!.toInt()
                "dark_theme" -> PrefManager.darkTheme = value!!.toInt()
                "app_language" -> PrefManager.appLanguage = value ?: "system"
                "update_channel" -> PrefManager.updateChannel = value ?: "release"
                else -> throw IllegalArgumentException("Invalid key $key")
            }
            PrefManager.makeWorldReadable()
            AppLogger.logApp(
                action = "Settings.putString ($key)",
                details = "配置项 $key 已更新为 $value"
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))

        setContentView(binding.root)
        theme.applyStyle(
            com.google.android.material.R.style.Theme_Material3_DynamicColors_DayNight_NoActionBar,
            true
        )
        setSupportActionBar(binding.toolbar)
        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings_container, SettingsPreferenceFragment())
                .commit()
        }
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    fun triggerManualUpdateCheck(pref: Preference) {
        pref.isEnabled = false
        showToast(getString(R.string.checking_for_updates))
        viewModel.manualCheckUpdates { availableUpdate ->
            if (isFinishing || isDestroyed) return@manualCheckUpdates
            pref.isEnabled = true
            if (availableUpdate != null) {
                showUpdateDialog(availableUpdate)
            } else {
                showToast("${getString(R.string.already_latest_version)} (${BuildConfig.VERSION_NAME})")
            }
        }
    }

    private fun showUpdateDialog(availableUpdate: UpdateChecker.Update) {
        updateDialogInstance?.dismiss()
        updateDialogInstance = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.update_available)
            .setMessage(availableUpdate.changelog)
            .setPositiveButton(getString(R.string.update_button)) { _, _ ->
                val view = layoutInflater.inflate(R.layout.update_dialog, null)
                val progress = view.findViewById<LinearProgressIndicator>(R.id.update_download_progress)
                val cancel = view.findViewById<AppCompatButton>(R.id.update_download_cancel)
                val downloadDialog = MaterialAlertDialogBuilder(this)
                    .setView(view)
                    .create()
                cancel.setOnClickListener {
                    viewModel.cancelDownload(this)
                    downloadDialog.dismiss()
                }
                lifecycleScope.launch {
                    viewModel.downloadState.collect { state ->
                        when (state) {
                            is MainViewModel.State.Downloading -> {
                                if (state.progress > 0) {
                                    progress.isIndeterminate = false
                                    progress.progress = state.progress
                                }
                            }
                            is MainViewModel.State.Done -> {
                                viewModel.openPackageInstaller(this@ActivitySettings, state.fileUri)
                                viewModel.clearUpdate()
                                downloadDialog.dismiss()
                            }
                            is MainViewModel.State.Failed -> {
                                Toast.makeText(
                                    this@ActivitySettings,
                                    R.string.bs_update_download_failed,
                                    Toast.LENGTH_LONG
                                ).show()
                                downloadDialog.dismiss()
                            }
                            else -> {}
                        }
                    }
                }
                viewModel.startDownload(this, availableUpdate)
                downloadDialog.show()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                viewModel.clearUpdate()
            }
            .show()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            onBackPressedDispatcher.onBackPressed()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    class SettingsPreferenceFragment : PreferenceFragmentCompat() {

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            preferenceManager?.preferenceDataStore = SettingPreferenceDataStore()
            setPreferencesFromResource(R.xml.preferences, rootKey)

            findPreference<DropDownPreference>("app_language")?.setOnPreferenceChangeListener { _, newValue ->
                val newLang = (newValue as? String) ?: "system"
                if (PrefManager.appLanguage != newLang) {
                    PrefManager.appLanguage = newLang
                    LocaleHelper.applyLanguage(newLang)
                }
                true
            }

            findPreference<Preference>("check_update_now")?.setOnPreferenceClickListener { pref ->
                (activity as? ActivitySettings)?.triggerManualUpdateCheck(pref)
                true
            }

            findPreference<EditTextPreference>("accuracy_level")?.let {
                it.summary = "${PrefManager.accuracy} m"
                it.setOnBindEditTextListener { editText ->
                    editText.inputType = InputType.TYPE_CLASS_NUMBER
                    editText.keyListener = DigitsKeyListener.getInstance("0123456789.,")
                    editText.addTextChangedListener(getCommaReplacerTextWatcher(editText))
                }

                it.setOnPreferenceChangeListener { preference, newValue ->
                    try {
                        newValue as String?
                        preference.summary = "$newValue m"
                    } catch (n: NumberFormatException) {
                        n.printStackTrace()
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.enter_valid_input),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    true
                }
            }

            findPreference<DropDownPreference>("dark_theme")?.setOnPreferenceChangeListener { _, newValue ->
                val newMode = (newValue as String).toInt()
                if (PrefManager.darkTheme != newMode) {
                    AppCompatDelegate.setDefaultNightMode(newMode)
                    activity?.recreate()
                }
                true
            }

            findPreference<Preference>("joystick_enabled")?.let {
                it.setOnPreferenceClickListener {
                    if (askOverlayPermission()) {
                        if (isJoystickRunning()) {
                            requireContext().stopService(Intent(context, JoystickService::class.java))
                            it.summary = getString(R.string.joystick_disabled)
                        } else if (PrefManager.isStarted) {
                            requireContext().startService(Intent(context, JoystickService::class.java))
                            it.summary = getString(R.string.joystick_enabled)
                        } else {
                            requireContext().showToast(requireContext().getString(R.string.location_not_select))
                        }
                    }
                    true
                }
            }
        }

        private fun isJoystickRunning(): Boolean {
            return JoystickService.isRunning
        }

        private fun askOverlayPermission(): Boolean {
            if (Settings.canDrawOverlays(context)) {
                return true
            }
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context?.applicationContext?.packageName}")
            )
            requireContext().startActivity(intent)
            return false
        }

        private fun getCommaReplacerTextWatcher(editText: EditText): TextWatcher {
            return object : TextWatcher {
                override fun beforeTextChanged(
                    charSequence: CharSequence,
                    i: Int,
                    i1: Int,
                    i2: Int
                ) {
                }

                override fun onTextChanged(
                    charSequence: CharSequence,
                    i: Int,
                    i1: Int,
                    i2: Int
                ) {
                }

                override fun afterTextChanged(editable: Editable) {
                    val text = editable.toString()
                    if (text.contains(",")) {
                        editText.setText(text.replace(",", "."))
                        editText.setSelection(editText.text.length)
                    }
                }
            }
        }
    }
}