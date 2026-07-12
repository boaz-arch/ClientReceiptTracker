package com.company.supportvisit

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.company.supportvisit.databinding.ActivityMainBinding
import com.company.supportvisit.graph.AuthManager
import com.company.supportvisit.utils.AppConfig
import com.company.supportvisit.graph.MicrosoftAuthManager

class MainActivity : AppCompatActivity() {

    private lateinit var binding:
            ActivityMainBinding


    @SuppressLint("SetTextI18n")
    private fun refreshUserDisplay() {

        MicrosoftAuthManager.refreshAccount(this) {

            runOnUiThread {

                if (MicrosoftAuthManager.isConnected()) {

                    val name =
                        MicrosoftAuthManager
                            .getEmail()
                            .substringBefore("@")

                    binding.tvUser.text =
                        "Signed in as $name"

                } else {

                    binding.tvUser.text =
                        "Signed in as Guest"
                }
            }
        }
    }

    @SuppressLint("SetTextI18n")
    override fun onCreate(
        savedInstanceState: Bundle?
    ) {

        super.onCreate(savedInstanceState)

        binding =
            ActivityMainBinding.inflate(
                layoutInflater
            )

        setContentView(binding.root)

        if (
            AppConfig.LOGIN_ENABLED &&
            !AuthManager.isLoggedIn()
        ) {

            startActivity(
                Intent(
                    this,
                    LoginActivity::class.java
                )
            )

            finish()

            return
        }


        binding.btnNewVisit.setOnClickListener {

            startActivity(
                Intent(
                    this,
                    NewVisitActivity::class.java
                )
            )
        }

        binding.btnPastVisits.setOnClickListener {

            startActivity(
                Intent(
                    this,
                    PastVisitsActivity::class.java
                )
            )
        }

        binding.btnSettings.setOnClickListener {

            startActivity(
                Intent(
                    this,
                    SettingsActivity::class.java
                )
            )
        }
    }

    override fun onResume() {

        super.onResume()

        refreshUserDisplay()
    }
}