// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
/**
 * Retention metadata and legal holds (Slice 3, ADR-041 decision D18): the retention class of each covered record
 * (verification evidence, deposit documents, replacement files), set by the database, and legal holds placed by one
 * system administrator and released by two. SNIGF holds no approved retention schedule, so nothing is ever disposed
 * of: {@code record_disposal_allowed} is false for every record. Reading retention data never opens a document.
 *
 * <p>Every write is a database function (V30); sub-packages are internal (ADR-001).
 */
@ApplicationModule(displayName = "Records retention")
package org.snigf.mvp.retention;

import org.springframework.modulith.ApplicationModule;
