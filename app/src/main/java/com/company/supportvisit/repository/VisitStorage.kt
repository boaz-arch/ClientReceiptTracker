package com.company.supportvisit.repository

import android.content.Context
import com.company.supportvisit.model.Visit
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object VisitStorage {

    private const val FILE_NAME = "visits.json"

    fun saveVisits(
        context: Context,
        visits: List<Visit>
    ) {
        try {

            val jsonArray = JSONArray()

            visits.forEach { visit ->

                val json = JSONObject()

                json.put("clientName", visit.clientName)
                json.put("visitDate", visit.visitDate)
                json.put("startTime", visit.startTime)
                json.put("endTime", visit.endTime)
                json.put("workDescription", visit.workDescription)
                json.put("clientEmail", visit.clientEmail)
                json.put("pdfLink", visit.pdfLink)

                jsonArray.put(json)
            }

            File(
                context.filesDir,
                FILE_NAME
            ).writeText(jsonArray.toString())

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun loadVisits(
        context: Context
    ): MutableList<Visit> {

        return try {

            val file = File(
                context.filesDir,
                FILE_NAME
            )

            if (!file.exists()) {
                return mutableListOf()
            }

            val content = file.readText()

            if (content.isBlank()) {
                return mutableListOf()
            }

            val jsonArray = JSONArray(content)

            val visits = mutableListOf<Visit>()

            for (i in 0 until jsonArray.length()) {

                val json = jsonArray.optJSONObject(i)
                    ?: continue

                visits.add(
                    Visit(
                        clientName = json.optString(
                            "clientName",
                            ""
                        ),
                        visitDate = json.optString(
                            "visitDate",
                            ""
                        ),
                        startTime = json.optString(
                            "startTime",
                            ""
                        ),
                        endTime = json.optString(
                            "endTime",
                            ""
                        ),
                        workDescription = json.optString(
                            "workDescription",
                            ""
                        ),
                        clientEmail = json.optString(
                            "clientEmail",
                            ""
                        ),
                        pdfLink = json.optString(
                            "pdfLink",
                            ""
                        )
                    )
                )
            }

            visits

        } catch (e: Exception) {

            e.printStackTrace()

            mutableListOf()
        }
    }
}