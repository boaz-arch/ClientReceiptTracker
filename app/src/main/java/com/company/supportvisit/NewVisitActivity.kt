package com.company.supportvisit

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.company.supportvisit.databinding.ActivityNewVisitBinding
import com.company.supportvisit.utils.DateUtils
import com.company.supportvisit.model.VisitRequest
import com.company.supportvisit.repository.GraphRepository
import com.company.supportvisit.signature.SignatureManager
import com.company.supportvisit.utils.VisitIdGenerator
import java.io.File
import android.widget.Toast
import com.company.supportvisit.utils.ValidationUtils
import androidx.lifecycle.lifecycleScope
import com.company.supportvisit.utils.ResultState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NewVisitActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNewVisitBinding

    private var startTime = ""
    private var endTime = ""

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {

        super.onCreate(savedInstanceState)

        binding =
            ActivityNewVisitBinding.inflate(
                layoutInflater
            )

        setContentView(binding.root)

        binding.tvDate.text =
            DateUtils.currentDate()

        binding.btnStart.setOnClickListener {

            startTime =
                DateUtils.currentTime()

            binding.tvStartTime.text =
                startTime
        }

        binding.btnEnd.setOnClickListener {

            endTime =
                DateUtils.currentTime()

            binding.tvEndTime.text =
                endTime
        }

        binding.btnClearSignature
            .setOnClickListener {

                binding.signaturePad.clear()
            }

        binding.btnSubmit.setOnClickListener {

            submitVisit()
        }
    }

    private fun submitVisit() {
        if (
            !ValidationUtils.isValidVisit(

                binding.etClientName.text.toString(),

                binding.etClientEmail.text.toString(),

                startTime,

                endTime
            )
        ) {

            Toast.makeText(

                this,

                "Please complete all fields",

                Toast.LENGTH_LONG

            ).show()

            return
        }

        val visitId =
            VisitIdGenerator.generate()

        val bitmap =
            binding.signaturePad.signatureBitmap

        val signatureFile =
            SignatureManager.saveSignature(

                this,

                bitmap,

                visitId
            )

        val visit = VisitRequest(

            visitId = visitId,

            clientName =
                binding.etClientName.text.toString(),

            visitDate =
                binding.tvDate.text.toString(),

            startTime =
                startTime,

            endTime =
                endTime,

            workDescription =
                binding.etDescription.text.toString(),

            clientEmail =
                binding.etClientEmail.text.toString()
        )

        lifecycleScope.launch {

            val result =
                withContext(
                    Dispatchers.IO
                ) {

                    GraphRepository.submitVisit(
                        visit,
                        signatureFile
                    )
                }

            when (result) {

                is ResultState.Success -> {

                    Toast.makeText(
                        this@NewVisitActivity,
                        "Visit Submitted",
                        Toast.LENGTH_LONG
                    ).show()

                    finish()
                }

                is ResultState.Error -> {

                    Toast.makeText(
                        this@NewVisitActivity,
                        result.message,
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }


}