// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.document.infrastructure.schedule;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.snigf.mvp.audit.SystemActor;
import org.snigf.mvp.document.application.DocumentService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Removes document files nobody references ({@code SNIGF_DOCUMENT_CLEANUP_CRON}), as the service
 * identity {@code document-cleanup}; a run that removes something is audited.
 */
@Component
class DocumentCleanupJob {

    static final String ACTOR = "document-cleanup";
    private static final Logger log = LoggerFactory.getLogger(DocumentCleanupJob.class);

    private final DocumentService documents;

    DocumentCleanupJob(DocumentService documents) {
        this.documents = documents;
    }

    @Scheduled(cron = "${snigf.document.cleanup-cron}")
    void run() {
        int removed = SystemActor.call(ACTOR, documents::removeOrphans);
        if (removed > 0) {
            log.info("Document cleanup: {} unreferenced file(s) removed", removed);
        }
    }
}
