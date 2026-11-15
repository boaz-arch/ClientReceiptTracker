package com.company.supportvisit.server

import org.slf4j.LoggerFactory

/** Carries out account deletions whose 14-day grace period has ended. */
object AccountPurge {

    private val log = LoggerFactory.getLogger("supportvisit.purge")

    /** Returns how many accounts were purged. */
    fun run(store: Store, files: FileStore, now: Long = System.currentTimeMillis()): Int {
        val due = store.dueDeletions(now)
        due.forEach { request ->
            var removed = 0
            if (!request.keepShared) {
                store.visitIdsCreatedBy(request.tenantId, request.userId).forEach { visitId ->
                    files.deleteVisit(request.tenantId, visitId)
                    store.tombstone(Store.Kind.VISITS, request.tenantId, visitId)
                    removed++
                }
            }
            store.cancelDeletion(request.tenantId, request.userId)
            log.info("Account purged: {} visit(s) removed, keepShared={}", removed, request.keepShared)
        }
        return due.size
    }
}
