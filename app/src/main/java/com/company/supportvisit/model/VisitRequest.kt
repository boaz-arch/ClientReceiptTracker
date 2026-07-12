package com.company.supportvisit.model

data class VisitRequest(

    val clientName: String,

    val visitDate: String,

    val startTime: String,

    val endTime: String,

    val workDescription: String,

    val clientEmail: String
)