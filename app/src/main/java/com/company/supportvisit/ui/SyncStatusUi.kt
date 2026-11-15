package com.company.supportvisit.ui

import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import com.company.supportvisit.R
import com.company.supportvisit.data.SyncError
import com.company.supportvisit.data.SyncStatus

@get:StringRes
val SyncStatus.labelRes: Int
    get() = when (this) {
        SyncStatus.PENDING -> R.string.status_pending
        SyncStatus.PDF_UPLOADED -> R.string.status_pdf_uploaded
        SyncStatus.EMAILED -> R.string.status_emailed
        SyncStatus.FAILED -> R.string.status_failed
    }

@get:ColorRes
val SyncStatus.colorRes: Int
    get() = when (this) {
        SyncStatus.PENDING, SyncStatus.PDF_UPLOADED -> R.color.status_pending
        SyncStatus.EMAILED -> R.color.status_done
        SyncStatus.FAILED -> R.color.status_failed
    }

@get:StringRes
val SyncError.messageRes: Int
    get() = when (this) {
        SyncError.SIGN_IN_REQUIRED -> R.string.sync_error_sign_in
        SyncError.NETWORK -> R.string.sync_error_network
        SyncError.SERVER -> R.string.sync_error_server
        SyncError.UNKNOWN -> R.string.sync_error_unknown
    }
