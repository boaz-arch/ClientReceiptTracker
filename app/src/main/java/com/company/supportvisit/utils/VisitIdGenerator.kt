package com.company.supportvisit.utils

object VisitIdGenerator {

    private var counter = 1

    fun generate(): String {

        return String.format(
            "SV-%06d",
            counter++
        )
    }
}