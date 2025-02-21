/*
 * Copyright (C) 2017 Yuriy Kulikov yuriy.kulikov.87@gmail.com
 * Copyright (C) 2012 Yuriy Kulikov yuriy.kulikov.87@gmail.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.better.alarm.presenter

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.better.alarm.R
import com.better.alarm.checkPermissions
import com.better.alarm.configuration.Layout
import com.better.alarm.configuration.Prefs
import com.better.alarm.configuration.globalInject
import com.better.alarm.configuration.globalLogger
import com.better.alarm.interfaces.IAlarmsManager
import com.better.alarm.logger.Logger
import com.better.alarm.lollipop
import com.better.alarm.model.AlarmValue
import com.better.alarm.model.Alarmtone
import com.better.alarm.util.Optional
import com.better.alarm.util.modify
import com.better.alarm.view.showRepeatAndDateDialog
import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.disposables.Disposable
import io.reactivex.disposables.Disposables
import io.reactivex.schedulers.Schedulers
import java.text.SimpleDateFormat
import java.util.*

/** Details activity allowing for fine-grained alarm modification */
class AlarmDetailsFragment : Fragment() {
  private val alarms: IAlarmsManager by globalInject()
  private val logger: Logger by globalLogger("AlarmDetailsFragment")
  private val prefs: Prefs by globalInject()
  private var disposables = CompositeDisposable()

  private var backButtonSub: Disposable = Disposables.disposed()
  private var disposableDialog = Disposables.disposed()

  private val alarmsListActivity by lazy { activity as AlarmsListActivity }
  private val store: UiStore by globalInject()

  private val rowHolder: RowHolder by lazy {
    RowHolder(fragmentView.findViewById(R.id.details_list_row_container), alarmId, prefs.layout())
  }

  private val editor: Observable<AlarmValue> by lazy {
    store.editing().filter { it.value.isPresent() }.map { it.value.get() }
  }

  private val alarmId: Int by lazy { store.editing().value!!.id }

  private val highlighter: ListRowHighlighter? by lazy {
    ListRowHighlighter.createFor(requireActivity().theme)
  }

  private lateinit var fragmentView: View

  private val ringtonePickerRequestCode = 42

  override fun onCreateView(
      inflater: LayoutInflater,
      container: ViewGroup?,
      savedInstanceState: Bundle?
  ): View {
    logger.trace { "Showing details of ${store.editing().value}" }

    val view =
        inflater.inflate(
            when (prefs.layout()) {
              Layout.CLASSIC -> R.layout.details_fragment_classic
              Layout.COMPACT -> R.layout.details_fragment_compact
              else -> R.layout.details_fragment_bold
            },
            container,
            false)
    this.fragmentView = view

    disposables = CompositeDisposable()

    onCreateTopRowView()
    onCreateLabelView()
    onCreateRepeatView()
    onCreateRingtoneView()
    onCreateDeleteOnDismissView()
    onCreatePrealarmView()
    onCreateBottomView()

    store.transitioningToNewAlarmDetails().takeFirst { isNewAlarm ->
      if (isNewAlarm) {
        showTimePicker()
      }
      store.transitioningToNewAlarmDetails().onNext(false)
    }

    return view
  }

  private fun onCreateBottomView() {
    fragmentView.findViewById<View>(R.id.details_activity_button_save).setOnClickListener {
      saveAlarm()
    }
    fragmentView.findViewById<View>(R.id.details_activity_button_revert).setOnClickListener {
      revert()
    }
  }

  private fun onCreateLabelView() {
    val label: EditText = fragmentView.findViewById(R.id.details_label)

    observeEditor { value ->
      if (value.label != label.text.toString()) {
        label.setText(value.label)
      }
    }

    label.addTextChangedListener(
        object : TextWatcher {
          override fun afterTextChanged(s: Editable?) {}

          override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

          override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            editor.takeFirst {
              if (it.label != s.toString()) {
                modify("Label") { prev -> prev.copy(label = s.toString(), isEnabled = true) }
              }
            }
          }
        })
  }

  private fun onCreateRepeatView() {
    fragmentView.findViewById<LinearLayout>(R.id.details_repeat_row).setOnClickListener {
      editor
          .firstOrError()
          .flatMap { value -> requireContext().showRepeatAndDateDialog(value) }
          .subscribe { fromDialog ->
            modify("Repeat dialog") { prev ->
              prev.copy(
                  isEnabled = true, daysOfWeek = fromDialog.daysOfWeek, date = fromDialog.date)
            }
          }
          .addTo(disposables)
    }

    val repeatTitle = fragmentView.findViewById<TextView>(R.id.details_repeat_title)
    val repeatSummary = fragmentView.findViewById<TextView>(R.id.details_repeat_summary)

    observeEditor { value ->
      repeatTitle.text =
          when {
            value.date != null -> requireContext().getString(R.string.date)
            else -> requireContext().getString(R.string.alarm_repeat)
          }

      repeatSummary.text =
          when {
            value.date != null -> SimpleDateFormat.getDateInstance().format(value.date.time)
            else -> value.daysOfWeek.toString(requireContext(), true)
          }
    }

    observeEditor { alarmValue ->
      val valid = alarmValue.isValid()
      fragmentView.findViewById<View>(R.id.details_activity_button_save).isEnabled = valid
      rowHolder.rowView.isEnabled = valid
      rowHolder.detailsCheckImageView.alpha = if (valid) 1f else .2f

      repeatSummary.setTextColor(
          requireActivity()
              .theme
              .resolveColor(if (valid) android.R.attr.colorForeground else R.attr.colorError))
    }
  }

  private fun AlarmValue.isValid(): Boolean {
    return when (date) {
      null -> true
      else -> {
        val nextTime =
            Calendar.getInstance().apply {
              timeInMillis = date.timeInMillis
              set(Calendar.HOUR_OF_DAY, hour)
              set(Calendar.MINUTE, minutes)
            }
        nextTime.after(Calendar.getInstance())
      }
    }
  }

  private fun onCreateDeleteOnDismissView() {
    val mDeleteOnDismissRow by lazy {
      fragmentView.findViewById(R.id.details_delete_on_dismiss_row) as LinearLayout
    }

    val mDeleteOnDismissCheckBox by lazy {
      fragmentView.findViewById(R.id.details_delete_on_dismiss_checkbox) as CheckBox
    }

    mDeleteOnDismissRow.setOnClickListener {
      modify("Delete on Dismiss") { value ->
        value.copy(isDeleteAfterDismiss = !value.isDeleteAfterDismiss, isEnabled = true)
      }
    }

    observeEditor { value ->
      mDeleteOnDismissCheckBox.isChecked = value.isDeleteAfterDismiss
      mDeleteOnDismissRow.visibility = if (value.isRepeatSet) View.GONE else View.VISIBLE
    }
  }

  private fun onCreatePrealarmView() {
    val mPreAlarmRow by lazy {
      fragmentView.findViewById(R.id.details_prealarm_row) as LinearLayout
    }

    val mPreAlarmCheckBox by lazy {
      fragmentView.findViewById(R.id.details_prealarm_checkbox) as CheckBox
    }

    // pre-alarm
    mPreAlarmRow.setOnClickListener {
      modify("Pre-alarm") { value -> value.copy(isPrealarm = !value.isPrealarm, isEnabled = true) }
    }

    observeEditor { value -> mPreAlarmCheckBox.isChecked = value.isPrealarm }

    // pre-alarm duration, if set to "none", remove the option
    prefs.preAlarmDuration
        .observe()
        .subscribe { value ->
          mPreAlarmRow.visibility = if (value.toInt() == -1) View.GONE else View.VISIBLE
        }
        .addTo(disposables)
  }

  private fun onCreateRingtoneView() {
    fragmentView.findViewById<LinearLayout>(R.id.details_ringtone_row).setOnClickListener {
      editor.takeFirst { value ->
        showRingtonePicker(value.alarmtone, ringtonePickerRequestCode, prefs.defaultRingtone())
      }
    }

    val ringtoneSummary by lazy {
      fragmentView.findViewById<TextView>(R.id.details_ringtone_summary)
    }

    Observable.combineLatest(
            editor.distinctUntilChanged().map { it.alarmtone },
            prefs.defaultRingtone.observe().map { Alarmtone.fromString(it) }) {
                value,
                defaultRingtone ->
              value to defaultRingtone
            }
        .observeOn(Schedulers.computation())
        .map { (alarmtone, default) ->
          when {
            // Default (title)
            // We need this case otherwise we will have "App default (Default (title))"
            alarmtone is Alarmtone.Default && default is Alarmtone.SystemDefault ->
                default.userFriendlyTitle(requireActivity())
            // App default (title)
            alarmtone is Alarmtone.Default ->
                getString(
                    R.string.app_default_ringtone, default.userFriendlyTitle(requireActivity()))
            // title
            else -> alarmtone.userFriendlyTitle(requireActivity())
          }
        }
        .observeOn(AndroidSchedulers.mainThread())
        .subscribe { ringtoneSummary.text = it }
        .addTo(disposables)
  }

  private fun onCreateTopRowView() =
      rowHolder.apply {
        daysOfWeek.visibility = View.INVISIBLE
        label.visibility = View.INVISIBLE

        lollipop {
          digitalClock.transitionName = "clock$alarmId"
          container.transitionName = "onOff$alarmId"
          detailsButton.transitionName = "detailsButton$alarmId"
        }

        digitalClock.setLive(false)

        val pickerClickTarget =
            if (layout == Layout.CLASSIC) digitalClockContainer else digitalClock

        container.setOnClickListener {
          modify("onOff") { value -> value.copy(isEnabled = !value.isEnabled) }
        }

        pickerClickTarget.setOnClickListener { showTimePicker() }

        rowView.setOnClickListener { saveAlarm() }

        observeEditor { value ->
          rowHolder.digitalClock.updateTime(
              Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, value.hour)
                set(Calendar.MINUTE, value.minutes)
              })

          rowHolder.onOff.isChecked = value.isEnabled

          highlighter?.applyTo(rowHolder, value.isEnabled)
        }

        animateCheck(check = true)
      }

  override fun onDestroyView() {
    super.onDestroyView()
    disposables.dispose()
  }

  @Deprecated("Deprecated in Java")
  override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    if (data != null && requestCode == ringtonePickerRequestCode) {
      val alarmtone = data.getPickedRingtone()
      checkPermissions(requireActivity(), listOf(alarmtone))
      logger.debug { "Picked alarm tone: $alarmtone" }
      modify("Ringtone picker") { prev -> prev.copy(alarmtone = alarmtone, isEnabled = true) }
    }
  }

  override fun onResume() {
    super.onResume()
    backButtonSub =
        store.onBackPressed().subscribe {
          editor.takeFirst {
            if (it.isValid()) {
              saveAlarm()
            }
          }
        }
  }

  override fun onPause() {
    super.onPause()
    disposableDialog.dispose()
    backButtonSub.dispose()
  }

  private fun saveAlarm() {
    editor.takeFirst { value ->
      alarms.getAlarm(alarmId)?.run { edit { withChangeData(value) } }
      store.hideDetails(rowHolder)
      animateCheck(check = false)
    }
  }

  private fun revert() {
    store.editing().value?.let { edited ->
      // "Revert" on a newly created alarm should delete it.
      if (edited.isNew) {
        alarms.getAlarm(edited.id)?.delete()
      }
      // else do not save changes
      store.hideDetails(rowHolder)
      animateCheck(check = false)
    }
  }

  private fun showTimePicker() {
    disposableDialog =
        TimePickerDialogFragment.showTimePicker(alarmsListActivity.supportFragmentManager)
            .subscribe { picked: Optional<PickedTime> ->
              if (picked.isPresent()) {
                modify("Picker") { value ->
                  value.copy(
                      hour = picked.get().hour, minutes = picked.get().minute, isEnabled = true)
                }
              }
            }
  }

  private fun modify(reason: String, function: (AlarmValue) -> AlarmValue) {
    logger.debug { "Performing modification because of $reason" }
    store.editing().modify { copy(value = value.map { function(it) }) }
  }

  private fun Disposable.addTo(disposables: CompositeDisposable) {
    disposables.add(this)
  }

  private fun animateCheck(check: Boolean) {
    rowHolder.detailsCheckImageView.animate().alpha(if (check) 1f else 0f).setDuration(500).start()
    rowHolder.detailsImageView.animate().alpha(if (check) 0f else 1f).setDuration(500).start()
  }

  private fun observeEditor(block: (value: AlarmValue) -> Unit) {
    editor.distinctUntilChanged().subscribe { block(it) }.addTo(disposables)
  }

  private fun <T : Any> Observable<T>.takeFirst(block: (value: T) -> Unit) {
    take(1).subscribe { block(it) }.addTo(disposables)
  }
}
