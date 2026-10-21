// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
/**
 * Accounts, authentication, sessions, second factors, role assignments scoped to offices.
 * Creating an account grants no authority over land records.
 *
 * <p>Published API: {@link org.snigf.mvp.identity.AuthenticatedUser} (the principal of every
 * authenticated request), {@link org.snigf.mvp.identity.AccountType},
 * {@link org.snigf.mvp.identity.AuthLevel}. Layers follow ADR-021: {@code domain} (accounts,
 * password and lockout rules), {@code application} (registration, login, session restore),
 * {@code infrastructure} (JDBC, Argon2id with the OpenBao pepper, Spring Security and
 * Spring Session configuration, HTTP endpoints, throttling).
 */
@ApplicationModule(displayName = "Identity and access")
package org.snigf.mvp.identity;

import org.springframework.modulith.ApplicationModule;
