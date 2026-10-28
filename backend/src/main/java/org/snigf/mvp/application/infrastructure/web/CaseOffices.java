// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.application.infrastructure.web;

import java.util.UUID;

import org.snigf.mvp.application.application.CaseService;
import org.springframework.stereotype.Component;

/**
 * The office of a submitted application, for the staff endpoints' permission checks:
 * {@code @access.canAt(authentication, 'completeness.review', @caseOffices.of(#id))}. It comes
 * from the database (the parcel's office at submission), never from the request. A draft or an
 * unknown id has no office: only a holder of the permission everywhere passes, and then gets 404.
 */
@Component("caseOffices")
public class CaseOffices {

    private final CaseService cases;

    public CaseOffices(CaseService cases) {
        this.cases = cases;
    }

    public String of(UUID id) {
        return id == null ? null : cases.officeOf(id);
    }
}
