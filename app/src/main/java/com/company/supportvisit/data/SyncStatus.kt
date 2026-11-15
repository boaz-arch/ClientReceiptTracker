package com.company.supportvisit.data

/**
 * Where a visit is in the submit pipeline. A visit is saved locally first (PENDING), then the
 * sync worker uploads the PDF (PDF_UPLOADED) and emails it (EMAILED). Each step is persisted,
 * so a retry resumes where it stopped instead of uploading or emailing twice.
 */
enum class SyncStatus {
    PENDING,
    PDF_UPLOADED,
    EMAILED,
    FAILED
}

/** Why a visit is FAILED, so the UI can show a helpful message instead of a raw error. */
enum class SyncError {
    SIGN_IN_REQUIRED,
    NETWORK,
    SERVER,
    UNKNOWN
}
