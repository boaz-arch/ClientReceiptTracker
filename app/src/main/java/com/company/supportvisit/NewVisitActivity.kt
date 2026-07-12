package com.company.supportvisit

import android.annotation.SuppressLint
import android.app.TimePickerDialog
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.company.supportvisit.databinding.ActivityNewVisitBinding
import com.company.supportvisit.graph.MailService
import com.company.supportvisit.graph.OneDriveService
import com.company.supportvisit.model.VisitRequest
import com.company.supportvisit.repository.GraphRepository
import com.company.supportvisit.repository.VisitCache
import com.company.supportvisit.repository.VisitStorage
import com.company.supportvisit.utils.DateUtils
import com.company.supportvisit.utils.PdfReportGenerator
import com.company.supportvisit.utils.ResultState
import com.company.supportvisit.utils.ValidationUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class NewVisitActivity : AppCompatActivity() {

    private lateinit var binding:
            ActivityNewVisitBinding

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

        setupTimePickers()

        binding.btnClearSignature
            .setOnClickListener {

                binding.signaturePad.clear()
            }

        binding.btnSubmit.setOnClickListener {

            submitVisit()
        }
    }

    private fun setupTimePickers() {

        binding.etStartTime.setOnClickListener {

            showTimePicker {

                binding.etStartTime.setText(it)
            }
        }

        binding.etEndTime.setOnClickListener {

            showTimePicker {

                binding.etEndTime.setText(it)
            }
        }
    }

    @SuppressLint("DefaultLocale")
    private fun showTimePicker(
        onTimeSelected: (String) -> Unit
    ) {

        val dialog =
            TimePickerDialog(
                this,
                { _, hour, minute ->

                    val time =
                        String.format(
                            "%02d:%02d",
                            hour,
                            minute
                        )

                    onTimeSelected(time)

                },
                9,
                0,
                true
            )

        dialog.show()
    }

    private fun submitVisit() {

        val startTime =
            binding.etStartTime.text
                .toString()

        val endTime =
            binding.etEndTime.text
                .toString()

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

        if (
            !isValidTimeRange(
                startTime,
                endTime
            )
        ) {

            Toast.makeText(
                this,
                "End time must be after start time",
                Toast.LENGTH_LONG
            ).show()

            return
        }

        if (
            binding.signaturePad.isEmpty
        ) {

            Toast.makeText(
                this,
                "Please provide a signature",
                Toast.LENGTH_LONG
            ).show()

            return
        }

        val bitmap =
            binding.signaturePad.signatureBitmap

        val visit =
            VisitRequest(

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

        val pdfFile =
            PdfReportGenerator.createReport(
                this,
                visit,
                bitmap
            )

        lifecycleScope.launch {

            val result =
                withContext(
                    Dispatchers.IO
                ) {

                    try {

                        val pdfLink =
                            OneDriveService.uploadFile(
                                pdfFile
                            )

                        val saveResult =
                            GraphRepository.submitVisit(
                                visit,
                                pdfFile,
                                pdfLink
                            )

                        if (
                            saveResult is ResultState.Success
                        ) {

                            MailService.sendVisitMail(
                                visit,
                                pdfFile
                            )
                        }

                        saveResult

                    } catch (e: Exception) {

                        ResultState.Error(
                            e.message
                                ?: "Unknown Error"
                        )
                    }
                }

            when (result) {

                is ResultState.Success -> {

                    VisitStorage.saveVisits(
                        this@NewVisitActivity,
                        VisitCache.visits
                    )

                    Toast.makeText(
                        this@NewVisitActivity,
                        "Visit Submitted Successfully",
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

    private fun isValidTimeRange(
        start: String,
        end: String
    ): Boolean {

        return try {

            val formatter =
                DateTimeFormatter.ofPattern(
                    "HH:mm"
                )

            val startTime =
                LocalTime.parse(
                    start,
                    formatter
                )

            val endTime =
                LocalTime.parse(
                    end,
                    formatter
                )

            endTime.isAfter(
                startTime
            )

        } catch (e: Exception) {

            false
        }
    }
}