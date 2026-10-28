// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.conflict.infrastructure.web;

import java.util.List;
import java.util.UUID;

import org.snigf.mvp.conflict.application.ConflictService;
import org.springframework.stereotype.Component;

/**
 * The offices of an alert's two parcels, for the endpoint's permission check:
 * {@code @access.canAtAny(authentication, 'alert.resolve', @alertOffices.of(#id))}. They come
 * from the database, never from the request. An unknown alert has none: only a holder of the
 * permission everywhere passes, and then gets 404.
 */
@Component("alertOffices")
public class AlertOffices {

    private final ConflictService conflicts;

    public AlertOffices(ConflictService conflicts) {
        this.conflicts = conflicts;
    }

    public List<String> of(UUID id) {
        return id == null ? List.of() : conflicts.officesOf(id);
    }
}
