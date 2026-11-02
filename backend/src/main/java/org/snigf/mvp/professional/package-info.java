// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
/**
 * External professional accounts (Slice 3, ADR-041): notaries and sworn surveyors request an account,
 * one administrator verifies their credential and proposes offices, another approves; one suspends,
 * two reinstate, revoke or change offices. DEMO only.
 *
 * <p>It creates accounts through {@code identity.AccountRegistration}, keeps verification evidence
 * through {@code document.DocumentFiles}, and grants no business permission by itself (step 3). Every
 * write is a database function (V24); sub-packages are internal (ADR-001).
 */
@ApplicationModule(displayName = "Professional accounts")
package org.snigf.mvp.professional;

import org.springframework.modulith.ApplicationModule;
