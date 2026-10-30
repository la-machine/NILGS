// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.issuance.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.snigf.mvp.application.ApplicationSubmitted;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Issues the DEMO acknowledgment once a submission has committed (ADR-039). A failure here never
 * undoes the submission: it is audited by the issuance service, logged, and the applicant can ask
 * again ({@code POST .../acknowledgment}), which issues the same single document.
 */
@Component
class SubmissionAcknowledgment {

    private static final Logger log = LoggerFactory.getLogger(SubmissionAcknowledgment.class);

    private final IssuanceService issuance;

    SubmissionAcknowledgment(IssuanceService issuance) {
        this.issuance = issuance;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void submitted(ApplicationSubmitted event) {
        try {
            issuance.issueSubmissionAcknowledgment(event.applicantId(), event.applicationId());
        } catch (RuntimeException e) {
            log.warn("Acknowledgment of application {} not issued now ({}); it can be issued again on request",
                    event.applicationId(), e.getMessage());
        }
    }
}
