// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.application.application;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.snigf.mvp.application.domain.ApplicationRepository;
import org.snigf.mvp.application.domain.ApplicationType;
import org.snigf.mvp.audit.AuditAction;
import org.snigf.mvp.audit.AuditEvent;
import org.snigf.mvp.audit.AuditOutcome;
import org.snigf.mvp.audit.AuditRecorder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps the type catalogue equal to its configuration (C4). A type whose definition changed
 * gets a new version in force from now; a type no longer configured gets a version marked
 * unavailable (new drafts refused, existing ones kept). Versions are never edited (V13). Each
 * change is audited; an invalid definition stops the start with every reason.
 */
@Service
public class ApplicationTypeCatalogue {

    static final String ENTITY = "APPLICATION_TYPE";

    private final ApplicationRepository applications;
    private final AuditRecorder audit;

    public ApplicationTypeCatalogue(ApplicationRepository applications, AuditRecorder audit) {
        this.applications = applications;
        this.audit = audit;
    }

    /** Applies the definitions; returns the number of versions written. */
    @Transactional
    public int apply(List<ApplicationType.Definition> definitions) {
        List<String> problems = new ArrayList<>();
        Set<String> codes = new HashSet<>();
        if (definitions.isEmpty()) {
            problems.add("at least one application type is required");
        }
        for (ApplicationType.Definition d : definitions) {
            problems.addAll(d.problems());
            if (!codes.add(d.code())) {
                problems.add("type " + d.code() + ": defined twice");
            }
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Invalid application type catalogue: " + String.join("; ", problems));
        }
        int written = 0;
        for (ApplicationType.Definition d : definitions) {
            Optional<ApplicationType> latest = applications.latestType(d.code());
            if (latest.isPresent() && latest.get().sameAs(d)) {
                continue;
            }
            int next = latest.map(t -> t.versionNo() + 1).orElse(1);
            applications.addTypeVersion(d, next, true);
            record(d.code(), next, true, latest);
            written++;
        }
        for (String code : applications.typeCodes()) {
            if (codes.contains(code)) {
                continue;
            }
            ApplicationType latest = applications.latestType(code).orElseThrow();
            if (latest.available()) {
                applications.addTypeVersion(new ApplicationType.Definition(code, latest.nameFr(), latest.nameEn(),
                        latest.requirements()), latest.versionNo() + 1, false);
                record(code, latest.versionNo() + 1, false, Optional.of(latest));
                written++;
            }
        }
        return written;
    }

    private void record(String code, int versionNo, boolean available, Optional<ApplicationType> previous) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("code", code);
        facts.put("versionNo", versionNo);
        facts.put("available", available);
        facts.put("previousVersionNo", previous.map(ApplicationType::versionNo).orElse(0));
        audit.record(AuditEvent.of(AuditAction.APPLICATION_TYPE_CHANGED, AuditOutcome.SUCCESS)
                .withEntityRef(code).withDetails(facts));
    }
}
