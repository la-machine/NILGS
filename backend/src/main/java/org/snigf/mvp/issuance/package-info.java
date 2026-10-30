// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
/**
 * Documents issued by SNIGF and their verification (Slice 1, ADR-039): a registry of every issued
 * document, its exact PDF, a manifest signed in key custody with a key of its own, public
 * verification by number or by file, statuses and the incident queue. DEMO only: the one type is
 * the application-submission acknowledgment, which establishes no right.
 *
 * <p>Uploaded documents are the {@code document} module's, never this one's: a file SNIGF did not
 * issue is never fraudulent for that reason. It listens to the application module's
 * {@code ApplicationSubmitted}; it publishes nothing of its own; sub-packages are internal (ADR-001).
 */
@ApplicationModule(displayName = "Issued documents")
package org.snigf.mvp.issuance;

import org.springframework.modulith.ApplicationModule;
