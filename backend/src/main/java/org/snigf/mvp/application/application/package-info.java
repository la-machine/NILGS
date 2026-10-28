// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
/**
 * Application use cases: the applicant's drafts (create, read, change, delete), the idempotent
 * submission, and keeping the type catalogue equal to its configuration. Every use case names
 * its actor to the database first ({@code DatabaseActor}). Knows the domain's ports, never the
 * adapters.
 */
package org.snigf.mvp.application.application;
