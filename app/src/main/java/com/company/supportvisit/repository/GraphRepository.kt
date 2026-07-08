package com.company.supportvisit.repository

import com.company.supportvisit.graph.MailService
import com.company.supportvisit.graph.OneDriveService
import com.company.supportvisit.graph.SharePointService
import com.company.supportvisit.graph.VisitParser
import com.company.supportvisit.model.Visit
import com.company.supportvisit.model.VisitRequest
import com.company.supportvisit.utils.ResultState
import java.io.File

object GraphRepository {

    fun submitVisit(
        visit: VisitRequest,
        signature: File
    ): ResultState {

        return try {

            val signatureUrl =
                OneDriveService.uploadFile(
                    signature
                )

            SharePointService.createVisit(
                visit,
                signatureUrl
            )

            MailService.sendVisitMail(
                visit,
                signatureUrl
            )

            ResultState.Success

        } catch (e: Exception) {

            ResultState.Error(
                e.message ?: "Unknown Error"
            )
        }
    }

    fun loadVisits(): List<Visit> {

        return try {

            val json =
                SharePointService.getVisits()

            if (json.isNullOrBlank()) {

                emptyList()
            } else {

                VisitParser.parse(json)
            }

        } catch (e: Exception) {

            e.printStackTrace()

            emptyList()
        }
    }
}