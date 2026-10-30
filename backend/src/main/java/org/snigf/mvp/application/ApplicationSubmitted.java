// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.application;

import java.util.UUID;

/**
 * Published when an application is submitted, inside the submitting transaction. Listeners that
 * act on it after the commit (the submission acknowledgment, ADR-039) never change the
 * submission's outcome: a failure there leaves the application submitted.
 */
public record ApplicationSubmitted(UUID applicationId, UUID applicantId) {
}
