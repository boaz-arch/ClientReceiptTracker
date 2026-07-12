package com.company.supportvisit.repository

import com.company.supportvisit.model.Visit
import com.company.supportvisit.model.VisitRequest
import com.company.supportvisit.utils.ResultState
import java.io.File

object GraphRepository {

    fun submitVisit(
        visit: VisitRequest,
        pdf: File,
        pdfLink: String
    ): ResultState {

        return try {

            VisitCache.visits.add(

                Visit(

                    clientName =
                        visit.clientName,

                    visitDate =
                        visit.visitDate,

                    startTime =
                        visit.startTime,

                    endTime =
                        visit.endTime,

                    workDescription =
                        visit.workDescription,

                    clientEmail =
                        visit.clientEmail,

                    pdfLink =
                        pdfLink
                )
            )

            ResultState.Success

        } catch (e: Exception) {

            ResultState.Error(
                e.message ?: "Unknown Error"
            )
        }
    }

    fun loadVisits(): List<Visit> {

        return VisitCache.visits
    }
}

