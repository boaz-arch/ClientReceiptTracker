package com.company.supportvisit

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.company.supportvisit.databinding.ActivitySettingsBinding
import com.company.supportvisit.excel.CsvExporter
import com.company.supportvisit.excel.ExcelExporter
import com.company.supportvisit.settings.ThemeManager
import com.company.supportvisit.utils.FileShareUtils
import com.company.supportvisit.utils.FileUtils

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)

        binding =
            ActivitySettingsBinding.inflate(layoutInflater)

        setContentView(binding.root)

        setupThemeButtons()

        setupExportButtons()
    }

    private fun setupThemeButtons() {

        binding.btnLight.setOnClickListener {

            ThemeManager.setLightMode()

            Toast.makeText(
                this,
                "Light Mode Enabled",
                Toast.LENGTH_SHORT
            ).show()
        }

        binding.btnDark.setOnClickListener {

            ThemeManager.setDarkMode()

            Toast.makeText(
                this,
                "Dark Mode Enabled",
                Toast.LENGTH_SHORT
            ).show()
        }

        binding.btnSystem.setOnClickListener {

            ThemeManager.setSystemMode()

            Toast.makeText(
                this,
                "System Theme Enabled",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun setupExportButtons() {

        binding.btnExportExcel.setOnClickListener {

            try {

                ExcelExporter.export(this)

                val file =
                    FileUtils.getExcelFile(
                        this
                    )

                FileShareUtils.shareFile(
                    this,
                    file
                )

            } catch (e: Exception) {

                Toast.makeText(
                    this,
                    "Excel export failed:\n${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        binding.btnExportCsv.setOnClickListener {

            try {

                CsvExporter.export(this)

                val file =
                    FileUtils.getCsvFile(
                        this
                    )

                FileShareUtils.shareFile(
                    this,
                    file
                )

            } catch (e: Exception) {

                Toast.makeText(
                    this,
                    "CSV export failed:\n${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}