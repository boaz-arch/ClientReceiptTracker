package com.company.supportvisit.repository

import com.company.supportvisit.model.Visit

object VisitCache {

    val visits =
        mutableListOf<Visit>()

    fun clear() {

        visits.clear()
    }
}