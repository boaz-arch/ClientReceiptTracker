package com.company.supportvisit

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.company.supportvisit.databinding.ActivityVisitDetailsBinding

class VisitDetailsActivity : AppCompatActivity() {

    private lateinit var binding:
            ActivityVisitDetailsBinding

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {

        super.onCreate(savedInstanceState)

        binding =
            ActivityVisitDetailsBinding.inflate(
                layoutInflater
            )



        setContentView(binding.root)

        val signatureUrl =
            intent.getStringExtra(
                "signatureUrl"
            ) ?: ""

        binding.tvSignatureUrl.text =
            signatureUrl

        val client =
            intent.getStringExtra("client")
                ?: ""

        val description =
            intent.getStringExtra("description")
                ?: ""

        val date =
            intent.getStringExtra("date")
                ?: ""

        val startTime =
            intent.getStringExtra("startTime")
                ?: ""

        val endTime =
            intent.getStringExtra("endTime")
                ?: ""

        val email =
            intent.getStringExtra("email")
                ?: ""

        val visitId =
            intent.getStringExtra("visitId")
                ?: ""

        binding.tvDetails.text =
            """
Support Visit Details

Visit ID:
$visitId

Client:
$client

Date:
$date

Start Time:
$startTime

End Time:
$endTime

Client Email:
$email

Work Description:
$description
            """.trimIndent()
    }
}