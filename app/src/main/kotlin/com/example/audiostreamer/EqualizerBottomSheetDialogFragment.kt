package com.example.audiostreamer

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import java.util.Locale

class EqualizerBottomSheetDialogFragment : BottomSheetDialogFragment() {

    private val sliders = mutableListOf<Slider>()
    private val gainLabels = mutableListOf<TextView>()
    private var isUpdatingUi = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.dialog_equalizer, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val eq = AudioCaptureService.masterEqualizer
        val prefs = requireContext().getSharedPreferences("stream_prefs", Context.MODE_PRIVATE)

        val switchEnabled = view.findViewById<MaterialSwitch>(R.id.switch_eq_enabled)
        val tvStatus = view.findViewById<TextView>(R.id.tv_eq_status)
        val chipGroupPresets = view.findViewById<ChipGroup>(R.id.chip_group_presets)
        val slidersContainer = view.findViewById<LinearLayout>(R.id.layout_sliders_container)
        val btnResetFlat = view.findViewById<MaterialButton>(R.id.btn_reset_flat)
        val btnClose = view.findViewById<MaterialButton>(R.id.btn_close_eq)

        switchEnabled.isChecked = eq.isEnabled
        updateStatusLabel(tvStatus, eq.isEnabled, eq.currentPreset)

        switchEnabled.setOnCheckedChangeListener { _, isChecked ->
            eq.isEnabled = isChecked
            eq.saveToPreferences(prefs)
            updateStatusLabel(tvStatus, isChecked, eq.currentPreset)
            updateSlidersEnabled(isChecked)
        }

        // Build 12 sliders
        sliders.clear()
        gainLabels.clear()
        slidersContainer.removeAllViews()

        for (i in 0 until Equalizer12Band.BAND_COUNT) {
            val row = layoutInflater.inflate(R.layout.item_eq_slider, slidersContainer, false)
            val tvFreq = row.findViewById<TextView>(R.id.tv_band_freq)
            val slider = row.findViewById<Slider>(R.id.slider_band_gain)
            val tvGain = row.findViewById<TextView>(R.id.tv_band_gain_val)

            tvFreq.text = Equalizer12Band.BAND_LABELS[i]
            val currentGain = eq.getBandGain(i)
            slider.value = currentGain
            tvGain.text = formatGain(currentGain)
            slider.isEnabled = eq.isEnabled

            slider.addOnChangeListener { _, value, fromUser ->
                if (fromUser && !isUpdatingUi) {
                    eq.setBandGain(i, value)
                    eq.saveToPreferences(prefs)
                    tvGain.text = formatGain(value)
                    chipGroupPresets.clearCheck()
                    updateStatusLabel(tvStatus, eq.isEnabled, "Custom")
                }
            }

            sliders.add(slider)
            gainLabels.add(tvGain)
            slidersContainer.addView(row)
        }

        // Setup preset chips
        val chipMap = mapOf(
            "Flat" to R.id.chip_preset_flat,
            "Bass Boost" to R.id.chip_preset_bass,
            "Treble Boost" to R.id.chip_preset_treble,
            "Rock" to R.id.chip_preset_rock,
            "Vocal" to R.id.chip_preset_vocal,
            "Electronic" to R.id.chip_preset_electronic,
            "Acoustic" to R.id.chip_preset_acoustic
        )

        chipMap[eq.currentPreset]?.let { chipId ->
            view.findViewById<Chip>(chipId)?.isChecked = true
        }

        chipGroupPresets.setOnCheckedStateChangeListener { _, checkedIds ->
            if (isUpdatingUi || checkedIds.isEmpty()) return@setOnCheckedStateChangeListener
            val selectedId = checkedIds.first()
            val presetName = chipMap.entries.firstOrNull { it.value == selectedId }?.key ?: return@setOnCheckedStateChangeListener

            eq.applyPreset(presetName)
            eq.saveToPreferences(prefs)
            updateSlidersFromEq()
            updateStatusLabel(tvStatus, eq.isEnabled, presetName)
        }

        btnResetFlat.setOnClickListener {
            eq.applyPreset("Flat")
            eq.saveToPreferences(prefs)
            updateSlidersFromEq()
            view.findViewById<Chip>(R.id.chip_preset_flat)?.isChecked = true
            updateStatusLabel(tvStatus, eq.isEnabled, "Flat")
        }

        btnClose.setOnClickListener {
            dismiss()
        }
    }

    private fun updateSlidersEnabled(enabled: Boolean) {
        sliders.forEach { it.isEnabled = enabled }
    }

    private fun updateSlidersFromEq() {
        isUpdatingUi = true
        val eq = AudioCaptureService.masterEqualizer
        for (i in 0 until Equalizer12Band.BAND_COUNT) {
            val gain = eq.getBandGain(i)
            sliders[i].value = gain
            gainLabels[i].text = formatGain(gain)
        }
        isUpdatingUi = false
    }

    private fun updateStatusLabel(tv: TextView, isEnabled: Boolean, preset: String) {
        tv.text = if (isEnabled) {
            "Active ($preset)"
        } else {
            "Disabled (Flat bypass)"
        }
    }

    private fun formatGain(gainDb: Float): String {
        return if (gainDb > 0.05f) {
            String.format(Locale.US, "+%.1f dB", gainDb)
        } else if (gainDb < -0.05f) {
            String.format(Locale.US, "%.1f dB", gainDb)
        } else {
            "0.0 dB"
        }
    }
}
