// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.shared.db;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Names, for the current transaction only, the account the application acts for
 * ({@code snigf.actor_id}, read by {@code current_actor()}). Row-level security on tables that
 * hold people's own records (applications, V13) shows a row only for that actor: a forgotten
 * ownership check in the code still returns nothing. Outside a transaction it refuses, because
 * the setting would not last until the query.
 */
@Component
public class DatabaseActor {

    private final JdbcClient jdbc;

    public DatabaseActor(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void bind(UUID actor) {
        if (actor == null) {
            throw new IllegalArgumentException("an actor is required");
        }
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("the database actor is bound inside a transaction");
        }
        jdbc.sql("SELECT set_config('snigf.actor_id', :actor, true)").param("actor", actor.toString())
                .query(String.class).single();
    }
}
