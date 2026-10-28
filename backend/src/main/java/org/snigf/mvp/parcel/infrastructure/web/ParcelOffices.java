// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.parcel.infrastructure.web;

import org.snigf.mvp.parcel.application.ParcelService;
import org.springframework.stereotype.Component;

/**
 * The office of a parcel, for the endpoint's permission check:
 * {@code @access.canAt(authentication, 'parcel.correct', @parcelOffices.of(#reference))}. The
 * office comes from the database, never from the request, so an officer cannot act on
 * another office's parcel by naming their own office. An unknown reference has no office:
 * only a holder of the permission everywhere passes, and then gets 404.
 */
@Component("parcelOffices")
public class ParcelOffices {

    private final ParcelService parcels;

    public ParcelOffices(ParcelService parcels) {
        this.parcels = parcels;
    }

    public String of(String reference) {
        String office = parcels.officeOf(reference);
        return office.isEmpty() ? null : office;
    }
}
