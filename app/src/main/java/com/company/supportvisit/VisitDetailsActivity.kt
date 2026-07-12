package com.company.supportvisit

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.company.supportvisit.databinding.ActivityVisitDetailsBinding
import androidx.core.net.toUri

class VisitDetailsActivity : AppCompatActivity() {

    private lateinit var binding:
            ActivityVisitDetailsBinding

    @SuppressLint("SetTextI18n")
    override fun onCreate(
        savedInstanceState: Bundle?
    ) {

        super.onCreate(savedInstanceState)

        binding =
            ActivityVisitDetailsBinding.inflate(
                layoutInflater
            )

        setContentView(binding.root)

        val pdfLink =
            intent.getStringExtra(
                "pdfLink"
            ) ?: ""

        val client =
            intent.getStringExtra(
                "client"
            ) ?: ""

        val description =
            intent.getStringExtra(
                "description"
            ) ?: ""

        val date =
            intent.getStringExtra(
                "date"
            ) ?: ""

        val startTime =
            intent.getStringExtra(
                "startTime"
            ) ?: ""

        val endTime =
            intent.getStringExtra(
                "endTime"
            ) ?: ""

        val email =
            intent.getStringExtra(
                "email"
            ) ?: ""

        binding.tvDetails.text =
            """
            Client: $client
            
            Date: $date
            
            Start Time: $startTime
            
            End Time: $endTime
            
            Email: $email
            
            Work Description:
            
            $description
            """.trimIndent()

        binding.tvSignatureUrl.text =
            "Open PDF Report"

        binding.tvSignatureUrl.setOnClickListener {

            if (pdfLink.isBlank()) {

                Toast.makeText(
                    this,
                    "PDF link not found",
                    Toast.LENGTH_LONG
                ).show()

                return@setOnClickListener
            }

            startActivity(

                Intent(
                    Intent.ACTION_VIEW,
                    pdfLink.toUri()
                )
            )
        }
    }
}