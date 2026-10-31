// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.register.infrastructure.web;

import java.util.UUID;

import org.snigf.mvp.register.application.RegisterService;
import org.springframework.stereotype.Component;

/**
 * The conservation of a register record, for the endpoints' permission checks:
 * {@code @access.canAt(authentication, 'register.read', @registerOffices.title(#id))}. It comes from
 * the database, never from the request. An unknown id has no office, so the check answers 403.
 */
@Component("registerOffices")
public class RegisterOffices {

    private final RegisterService register;

    public RegisterOffices(RegisterService register) {
        this.register = register;
    }

    public String title(UUID id) {
        return register.officeOf("TITLE", id);
    }

    public String inscription(UUID id) {
        return register.officeOf("INSCRIPTION", id);
    }

    public String transaction(UUID id) {
        return register.officeOf("TRANSACTION", id);
    }

    public String conflict(UUID id) {
        return register.officeOf("CONFLICT", id);
    }
}
