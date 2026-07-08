package com.company.supportvisit.model

data class Visit(

    val id: String,

    val visitId: String,

    val clientName: String,

    val visitDate: String,

    val startTime: String,

    val endTime: String,

    val workDescription: String,

    val clientEmail: String,

    val signatureUrl: String
)
