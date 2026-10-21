// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
/**
 * Key custody (ADR-018): signing, envelope encryption and HMAC with keys the application
 * never holds. Other modules use only {@link org.snigf.mvp.keys.KeyService} and the value
 * types in this package; the OpenBao client and the test-only in-memory provider are
 * internal.
 */
@ApplicationModule(displayName = "Keys")
package org.snigf.mvp.keys;

import org.springframework.modulith.ApplicationModule;
