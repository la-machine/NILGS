// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
/**
 * Officer-signed records (ADR-019), a shared service: canonical bytes, the officer's WebAuthn
 * signature, the system's {@code RECORD} signature, and their verification. It holds no records
 * itself; the module that owns a record stores what this returns. First used by the land register
 * (ADR-040); review decisions (ADR-035) adopt it in a separate change.
 */
@ApplicationModule(displayName = "Signed records")
package org.snigf.mvp.records;

import org.springframework.modulith.ApplicationModule;
