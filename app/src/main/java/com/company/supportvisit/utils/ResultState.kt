package com.company.supportvisit.utils

sealed class ResultState {

    data object Success : ResultState()

    data class Error(
        val message: String
    ) : ResultState()
}