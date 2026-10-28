// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.parcel.application;

import java.util.Optional;

import org.snigf.mvp.parcel.ParcelDirectory;
import org.snigf.mvp.parcel.domain.Parcel;
import org.snigf.mvp.parcel.domain.ParcelReference;
import org.snigf.mvp.parcel.domain.ParcelRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** {@link ParcelDirectory} over the parcel repository: a malformed reference is never looked up. */
@Service
public class ParcelDirectoryService implements ParcelDirectory {

    private final ParcelRepository parcels;

    public ParcelDirectoryService(ParcelRepository parcels) {
        this.parcels = parcels;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ActiveParcel> active(String reference) {
        if (!ParcelReference.isValid(reference)) {
            return Optional.empty();
        }
        return parcels.find(new ParcelReference(reference))
                .filter(p -> p.status() == Parcel.Status.ACTIVE)
                .flatMap(p -> parcels.officeId(p.officeCode())
                        .map(office -> new ActiveParcel(p.id(), p.reference().value(), office, p.officeCode())));
    }
}
