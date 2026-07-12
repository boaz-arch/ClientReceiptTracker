package com.company.supportvisit

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.company.supportvisit.databinding.ActivitySettingsBinding
import com.company.supportvisit.graph.AuthManager
import com.company.supportvisit.repository.VisitCache
import com.company.supportvisit.utils.CsvExporter
import android.widget.Toast
import com.company.supportvisit.graph.MicrosoftAuthManager

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding:
            ActivitySettingsBinding

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {

        super.onCreate(savedInstanceState)

        binding =
            ActivitySettingsBinding.inflate(
                layoutInflater
            )

        setContentView(binding.root)

        binding.btnLightMode.setOnClickListener {

            AppCompatDelegate.setDefaultNightMode(
                AppCompatDelegate.MODE_NIGHT_NO
            )
        }

        binding.btnDarkMode.setOnClickListener {

            AppCompatDelegate.setDefaultNightMode(
                AppCompatDelegate.MODE_NIGHT_YES
            )
        }

        binding.btnSystemMode.setOnClickListener {

            AppCompatDelegate.setDefaultNightMode(
                AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            )
        }

        binding.btnChangeLogin.setOnClickListener {

            startActivity(
                Intent(
                    this,
                    ChangeLoginActivity::class.java
                )
            )
        }

        binding.btnLogout.setOnClickListener {

            AuthManager.logout()

            startActivity(
                Intent(
                    this,
                    LoginActivity::class.java
                )
            )

            finishAffinity()
        }

        binding.btnExportCsv.setOnClickListener {

            val file =
                CsvExporter.exportVisits(
                    this,
                    VisitCache.visits
                )

            val uri =
                androidx.core.content.FileProvider.getUriForFile(
                    this,
                    "${applicationContext.packageName}.provider",
                    file
                )

            val shareIntent =
                Intent(
                    Intent.ACTION_SEND
                ).apply {

                    type = "text/csv"

                    putExtra(
                        Intent.EXTRA_STREAM,
                        uri
                    )

                    addFlags(
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }

            startActivity(

                Intent.createChooser(
                    shareIntent,
                    "Share CSV"
                )
            )
        }

        binding.btnConnectMicrosoft.setOnClickListener {

            MicrosoftAuthManager.connect(

                this,

                {

                    Toast.makeText(
                        this,
                        "Connected: $it\nToken Length: ${
                            MicrosoftAuthManager
                                .getToken()
                                .length
                        }",
                        Toast.LENGTH_LONG
                    ).show()
                },

                {

                    android.app.AlertDialog.Builder(this)

                        .setTitle(
                            "Microsoft Login Error"
                        )

                        .setMessage(it)

                        .setPositiveButton(
                            "OK",
                            null
                        )

                        .show()
                }
            )
        }

        binding.btnDisconnectMicrosoft.setOnClickListener {

            MicrosoftAuthManager.disconnect()

            Toast.makeText(
                this,
                "Microsoft Account Disconnected",
                Toast.LENGTH_LONG
            ).show()
        }

    }
}