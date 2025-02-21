package com.better.alarm.presenter

import android.content.ContentResolver
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Vibrator
import android.provider.Settings
import androidx.preference.CheckBoxPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import com.better.alarm.R
import com.better.alarm.checkPermissions
import com.better.alarm.configuration.Prefs
import com.better.alarm.configuration.globalInject
import com.better.alarm.configuration.globalLogger
import com.better.alarm.logger.Logger
import com.better.alarm.lollipop
import com.better.alarm.model.Alarmtone
import com.better.alarm.stores.RxDataStore
import com.better.alarm.view.VolumePreference
import io.reactivex.disposables.CompositeDisposable

/** Created by Yuriy on 24.07.2017. */
class SettingsFragment : PreferenceFragmentCompat() {
  private val alarmStreamTypeBit = 1 shl AudioManager.STREAM_ALARM
  private val vibrator: Vibrator by globalInject()
  private val prefs: Prefs by globalInject()
  private val disposables = CompositeDisposable()
  private val logger: Logger by globalLogger("SettingsFragment")

  private val contentResolver: ContentResolver
    get() = requireActivity().contentResolver

  companion object {
    const val themeChangeReason = "theme change"
  }

  override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
    addPreferencesFromResource(R.xml.preferences)

    val category: PreferenceCategory = findPreference("preference_category_sound_key")!!

    if (!vibrator.hasVibrator()) {
      findPreference<Preference>(Prefs.KEY_VIBRATE)?.let { category.removePreference(it) }
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      findPreference<Preference>(Prefs.KEY_ALARM_IN_SILENT_MODE)?.let {
        category.removePreference(it)
      }
    }

    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
      findPreference<PreferenceCategory>("preference_category_ui")?.run {
        findPreference<Preference>(Prefs.LIST_ROW_LAYOUT)?.let { rowLayout ->
          removePreference(rowLayout)
        }
      }

      findPreference<ListPreference>(Prefs.KEY_THEME)?.apply {
        entries = entries.take(2).toTypedArray()
        entryValues = entryValues.take(2).toTypedArray()
      }
    }

    findPreference<Preference>(Prefs.KEY_THEME)?.onPreferenceChangeListener =
        Preference.OnPreferenceChangeListener { _, _ ->
          Handler(Looper.getMainLooper()).post {
            requireActivity()
                .packageManager
                .getLaunchIntentForPackage(requireActivity().packageName)
                ?.apply {
                  addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                  putExtra("reason", themeChangeReason)
                }
                ?.let { startActivity(it) }
          }
          true
        }
  }

  @Deprecated("Deprecated in Java")
  override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    if (data == null) return
    val alarmtone = data.getPickedRingtone()
    check(alarmtone !is Alarmtone.Default) { "Default alarmtone is not allowed here" }
    logger.debug { "Picked default alarm tone: $alarmtone" }
    prefs.defaultRingtone.value = alarmtone.asString()
    checkPermissions(
        requireActivity(),
        listOf(alarmtone),
    )
  }

  override fun onPreferenceTreeClick(preference: Preference): Boolean {
    when (preference.key) {
      Prefs.KEY_ALARM_IN_SILENT_MODE -> {
        val pref = preference as CheckBoxPreference

        val ringerModeStreamTypes =
            when {
              pref.isChecked -> systemModeRingerStreamsAffected() and alarmStreamTypeBit.inv()
              else -> systemModeRingerStreamsAffected() or alarmStreamTypeBit
            }

        Settings.System.putInt(
            contentResolver, Settings.System.MODE_RINGER_STREAMS_AFFECTED, ringerModeStreamTypes)

        return true
      }
      else -> return super.onPreferenceTreeClick(preference)
    }
  }

  override fun onResume() {
    super.onResume()
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
      val alarmInSilentModePref =
          findPreference<CheckBoxPreference>(Prefs.KEY_ALARM_IN_SILENT_MODE)!!
      alarmInSilentModePref.isChecked =
          (systemModeRingerStreamsAffected() and alarmStreamTypeBit) == 0
    }

    findPreference<VolumePreference>(Prefs.KEY_VOLUME_PREFERENCE)?.run {
      showPicker = {
        val current = Alarmtone.fromString(prefs.defaultRingtone.value)
        showRingtonePicker(current, 43)
      }
      prefs.defaultRingtone
          .observe()
          .map { Alarmtone.fromString(it) }
          .subscribe { alarmtone ->
            val summary = alarmtone.userFriendlyTitle(requireContext())
            logger.debug { "Setting summary to $summary" }
            ringtoneTitle = summary
          }
          .also { disposables.add(it) }
    }

    bindListPreference(Prefs.KEY_ALARM_SNOOZE, prefs.snoozeDuration) { duration ->
      val idx = findIndexOfValue(duration.toString())
      summary = entries[idx]
    }

    bindListPreference(Prefs.KEY_AUTO_SILENCE, prefs.autoSilence) { newValue ->
      summary =
          when (newValue) {
            -1 -> getString(R.string.auto_silence_never)
            else -> getString(R.string.auto_silence_summary, newValue)
          }
    }

    bindListPreference(Prefs.KEY_SKIP_DURATION, prefs.skipDuration) { skipDuration ->
      val indexOfValue = findIndexOfValue(skipDuration.toString())
      summary = entries[indexOfValue]
    }

    bindListPreference(Prefs.KEY_PREALARM_DURATION, prefs.preAlarmDuration) { newValue ->
      summary =
          when (newValue) {
            -1 -> getString(R.string.prealarm_off_summary)
            else -> getString(R.string.prealarm_summary, newValue)
          }
    }

    bindListPreference(Prefs.KEY_FADE_IN_TIME_SEC, prefs.fadeInTimeInSeconds) { newValue ->
      summary =
          when (newValue) {
            1 -> getString(R.string.fade_in_off_summary)
            else -> getString(R.string.fade_in_summary, newValue)
          }
    }

    bindListPreference(Prefs.KEY_THEME, prefs.theme) { summary = entry }

    lollipop { bindListPreference(Prefs.LIST_ROW_LAYOUT, prefs.listRowLayout) { summary = entry } }
  }

  override fun onPause() {
    disposables.dispose()
    findPreference<VolumePreference>(Prefs.KEY_VOLUME_PREFERENCE)?.onPause()
    super.onPause()
  }

  private fun <S> bindListPreference(
      key: String,
      store: RxDataStore<S>,
      func: (ListPreference).(S) -> Unit
  ) {
    val preference =
        requireNotNull(findPreference<ListPreference>(key)) {
          "Preference with key: $key not found"
        }
    disposables.add(store.observe().subscribe { next -> func(preference, next) })
  }

  private fun systemModeRingerStreamsAffected(): Int {
    return Settings.System.getInt(contentResolver, Settings.System.MODE_RINGER_STREAMS_AFFECTED, 0)
  }
}
