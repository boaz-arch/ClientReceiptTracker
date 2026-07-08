package com.company.supportvisit

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.company.supportvisit.databinding.ActivityMainBinding
import com.company.supportvisit.graph.AuthManager

class MainActivity : AppCompatActivity() {

    private lateinit var binding:
            ActivityMainBinding

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {

        super.onCreate(savedInstanceState)

        binding =
            ActivityMainBinding.inflate(
                layoutInflater
            )

        setContentView(binding.root)

        AuthManager.signIn(
            this,
            {

                Toast.makeText(
                    this,
                    "Microsoft Login Success",
                    Toast.LENGTH_LONG
                ).show()

            },
            {

                Toast.makeText(
                    this,
                    it,
                    Toast.LENGTH_LONG
                ).show()
            }
        )

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
}