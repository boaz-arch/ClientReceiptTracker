package com.company.supportvisit.server

/** Who is calling: their company (tenant) and identity, after sign-in and membership checks. */
data class Caller(
    val tenantId: String,
    val userId: String,
    val email: String,
    val name: String
)

/** 401: not signed in, or the session is no longer valid. */
class AuthException(message: String) : Exception(message)

/** 409: signed in, but not (yet) part of a company. */
class NoCompanyException(val invitedTo: String? = null) : Exception("no_company")
