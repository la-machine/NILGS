// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
/**
 * Audit adapters: PostgreSQL repositories ({@code persistence}), the checkpoint file
 * archive ({@code archive}), the security and request context ({@code security}), the
 * health contributor ({@code health}), the daily jobs ({@code scheduling}) and settings
 * ({@code config}). Adapters implement domain repositories and application ports; nothing
 * in {@code domain} or {@code application} depends on them.
 */
package org.snigf.mvp.audit.infrastructure;
