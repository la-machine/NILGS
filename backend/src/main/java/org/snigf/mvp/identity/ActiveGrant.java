// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.identity;

import java.time.Instant;
import java.util.UUID;

/**
 * A special grant in force for the current user (ADR-025): one permission, everywhere or at
 * one office, until a date and/or for a number of uses.
 *
 * @param id         the grant
 * @param permission permission code
 * @param officeCode office, or null for everywhere
 * @param validUntil end, or null for no end date
 * @param usesLeft   remaining uses, or null for unlimited
 */
public record ActiveGrant(UUID id, String permission, String officeCode, Instant validUntil, Integer usesLeft) {
}
