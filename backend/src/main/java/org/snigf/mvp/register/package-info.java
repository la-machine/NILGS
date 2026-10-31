// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
/**
 * The DEMO land register (Slice 2, ADR-040): titles, append-only inscriptions signed by officers,
 * holders and charges derived by the database, pending transactions and their conflicts. Nothing
 * here is a legal registration, and all its data is synthetic. A title is not a parcel, a decision
 * or a payment: only a registered inscription changes what the register says.
 *
 * <p>It publishes nothing; it uses {@code records} to sign and {@code identity} and {@code audit} as
 * every module does. Every write is one database function (V21); sub-packages are internal (ADR-001).
 */
@ApplicationModule(displayName = "Land register")
package org.snigf.mvp.register;

import org.springframework.modulith.ApplicationModule;
