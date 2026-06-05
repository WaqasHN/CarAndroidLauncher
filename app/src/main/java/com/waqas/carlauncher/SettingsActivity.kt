package com.waqas.carlauncher

import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.waqas.carlauncher.databinding.ActivitySettingsBinding
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val fmt = SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.backButton.setOnClickListener { finish() }

        val systemNow = Date()
        val correctedNow = DateOffset.correctedNow(this)
        val offset = DateOffset.getOffsetDays(this)

        binding.systemDateText.text = getString(R.string.system_date_label, fmt.format(systemNow))
        binding.correctedDateText.text = getString(R.string.corrected_date_label, fmt.format(correctedNow))
        binding.currentOffsetText.text = getString(R.string.current_offset_label, offset)

        val initial = Calendar.getInstance().apply { time = correctedNow }
        binding.datePicker.init(
            initial.get(Calendar.YEAR),
            initial.get(Calendar.MONTH),
            initial.get(Calendar.DAY_OF_MONTH),
            null
        )

        binding.saveButton.setOnClickListener {
            val picked = Calendar.getInstance().apply {
                set(
                    binding.datePicker.year,
                    binding.datePicker.month,
                    binding.datePicker.dayOfMonth,
                    0, 0, 0
                )
                set(Calendar.MILLISECOND, 0)
            }
            val days = DateOffset.computeOffsetDays(picked)
            DateOffset.setOffsetDays(this, days)
            Toast.makeText(
                this,
                getString(R.string.offset_saved, days),
                Toast.LENGTH_SHORT
            ).show()
            finish()
        }

        binding.resetButton.setOnClickListener {
            DateOffset.setOffsetDays(this, 0L)
            Toast.makeText(this, R.string.offset_reset, Toast.LENGTH_SHORT).show()
            finish()
        }

        binding.fullScreenSwitch.isChecked = UiState.isFullScreen(this)
        binding.fullScreenSwitch.setOnCheckedChangeListener { _, isChecked ->
            UiState.setFullScreen(this, isChecked)
        }

        binding.speedometerSwitch.isChecked = UiState.isSpeedometerEnabled(this)
        binding.speedometerHomeSwitch.isChecked = UiState.isSpeedometerOnHome(this)
        binding.speedometerWhenMovingSwitch.isChecked = UiState.isSpeedometerWhenMoving(this)
        // Style spinner: index 0 = analog dial, 1 = digital readout.
        binding.speedometerStyleSpinner.setSelection(if (UiState.isSpeedometerDigital(this)) 1 else 0)
        refreshSpeedometerSubOptions()

        binding.speedometerSwitch.setOnCheckedChangeListener { _, isChecked ->
            UiState.setSpeedometerEnabled(this, isChecked)
            refreshSpeedometerSubOptions()
        }
        binding.speedometerHomeSwitch.setOnCheckedChangeListener { _, isChecked ->
            UiState.setSpeedometerOnHome(this, isChecked)
            refreshSpeedometerSubOptions()
        }
        binding.speedometerWhenMovingSwitch.setOnCheckedChangeListener { _, isChecked ->
            UiState.setSpeedometerWhenMoving(this, isChecked)
        }
        binding.speedometerStyleSpinner.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    UiState.setSpeedometerDigital(this@SettingsActivity, position == 1)
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
    }

    /** Style and movement options are shared, so they apply if the gauge is shown anywhere. */
    private fun refreshSpeedometerSubOptions() {
        val anywhere = binding.speedometerSwitch.isChecked || binding.speedometerHomeSwitch.isChecked
        binding.speedometerWhenMovingSwitch.isEnabled = anywhere
        binding.speedometerStyleSpinner.isEnabled = anywhere
    }
}
