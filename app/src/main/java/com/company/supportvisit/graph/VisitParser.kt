package com.company.supportvisit.graph

import com.company.supportvisit.model.Visit
import org.json.JSONArray
import org.json.JSONObject

object VisitParser {

    fun parse(
        json: String
    ): List<Visit> {

        val visits =
            mutableListOf<Visit>()

        val root =
            JSONObject(json)

        val value =
            root.getJSONArray("value")

        for (i in 0 until value.length()) {

            val item =
                value.getJSONObject(i)

            val fields =
                item.getJSONObject("fields")

            visits.add(

                Visit(

                    id =
                        item.optString(
                            "id"
                        ),

                    visitId =
                        fields.optString(
                            "VisitID"
                        ),

                    clientName =
                        fields.optString(
                            "Title"
                        ),

                    visitDate =
                        fields.optString(
                            "VisitDate"
                        ),

                    startTime =
                        fields.optString(
                            "StartTime"
                        ),

                    endTime =
                        fields.optString(
                            "EndTime"
                        ),

                    workDescription =
                        fields.optString(
                            "WorkDescription"
                        ),

                    clientEmail =
                        fields.optString(
                            "ClientEmail"
                        ),

                    signatureUrl =
                        fields.optString(
                            "SignatureUrl"
                        )
                )
            )
        }

        return visits
    }
}