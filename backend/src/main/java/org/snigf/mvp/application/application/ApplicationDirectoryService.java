// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.application.application;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.snigf.mvp.application.ApplicationDirectory;
import org.snigf.mvp.application.ApplicationWorkflow;
import org.snigf.mvp.application.domain.Application;
import org.snigf.mvp.application.domain.ApplicationRepository;
import org.snigf.mvp.application.domain.ApplicationState;
import org.snigf.mvp.application.domain.CompletenessRecord;
import org.snigf.mvp.shared.db.DatabaseActor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link ApplicationDirectory} and {@link ApplicationWorkflow}: the actor is named to the database
 * before anything is read or moved.
 */
@Service
public class ApplicationDirectoryService implements ApplicationDirectory, ApplicationWorkflow {

    private final ApplicationRepository applications;
    private final DatabaseActor actor;

    public ApplicationDirectoryService(ApplicationRepository applications, DatabaseActor actor) {
        this.applications = applications;
        this.actor = actor;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<OwnApplication> own(UUID user, UUID applicationId) {
        actor.bind(user);
        return applications.find(applicationId).filter(a -> a.ownedBy(user)).map(a -> view(a, open(a)));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<OwnApplication> forStaff(UUID user, UUID applicationId) {
        actor.bind(user);
        return applications.findCase(applicationId).map(c -> view(c.application(), List.of()));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<CaseState> visible(UUID user, UUID applicationId) {
        actor.bind(user);
        return applications.findCase(applicationId).map(c -> new CaseState(c.application().id(), c.application().reference(),
                c.application().state().name(), c.application().version(), c.application().officeCode(),
                c.application().applicantId()));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean move(UUID user, UUID applicationId, long expectedVersion, String from, String to, String reason) {
        actor.bind(user);
        return applications.move(applicationId, expectedVersion, ApplicationState.valueOf(from), ApplicationState.valueOf(to),
                user, reason);
    }

    /** What the owner may add files to now: anything in a draft, what was asked for during a request. */
    private List<String> open(Application a) {
        List<String> all = applications.type(a.typeVersionId()).orElseThrow().requirements().documents().stream()
                .map(d -> d.code()).toList();
        if (a.state() == ApplicationState.DRAFT) {
            return all;
        }
        if (a.state() == ApplicationState.INFO_REQUESTED) {
            return applications.completeness(a.id()).stream()
                    .filter(r -> r.kind() == CompletenessRecord.Kind.INFO_REQUESTED)
                    .reduce((first, second) -> second)
                    .map(CompletenessRecord::requested).orElse(List.of());
        }
        return List.of();
    }

    private OwnApplication view(Application a, List<String> open) {
        var type = applications.type(a.typeVersionId()).orElseThrow();
        return new OwnApplication(a.id(), a.reference(), a.state().name(), open,
                type.requirements().documents().stream()
                        .map(d -> new DocumentCategory(d.code(), d.required(), d.labelFr(), d.labelEn())).toList());
    }
}
