package com.company.supportvisit

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.company.supportvisit.databinding.ActivityReportSettingsBinding
import com.company.supportvisit.settings.FormSettings
import com.company.supportvisit.settings.FormSettings.Section
import com.company.supportvisit.ui.applySystemBarInsets
import com.google.android.material.switchmaterial.SwitchMaterial

/** Settings > Customize report: choose which optional sections the visit report has. */
class ReportSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReportSettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityReportSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarInsets()

        bind(binding.switchVisitType, Section.VISIT_TYPE)
        bind(binding.switchPhotos, Section.PHOTOS)
        bind(binding.switchParts, Section.PARTS)
        bind(binding.switchTechnicianSignature, Section.TECHNICIAN_SIGNATURE)
    }

    private fun bind(switch: SwitchMaterial, section: Section) {
        switch.isChecked = FormSettings.isEnabled(this, section)
        switch.setOnCheckedChangeListener { _, checked -> FormSettings.setEnabled(this, section, checked) }
    }
}
