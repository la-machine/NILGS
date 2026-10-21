// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.audit.application;

import org.snigf.mvp.audit.ChainVerification;

/** Port: makes the latest verdict on the whole chain visible to monitoring. */
public interface AuditChainMonitor {

    void report(ChainVerification verification);
}
