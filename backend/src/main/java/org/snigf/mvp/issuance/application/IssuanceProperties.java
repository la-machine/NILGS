// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.issuance.application;

import java.net.URI;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Issued-document settings under {@code snigf.issuance.*}, from {@code SNIGF_ISSUANCE_*} and
 * {@code SNIGF_RATE_VERIFY_PER_MINUTE} in .env (ADR-039).
 *
 * @param verifyBaseUrl     the public address of the verification page, which the QR code names
 *                          ({@code SNIGF_ISSUANCE_VERIFY_BASE_URL}); https only
 * @param maxUploadBytes    the largest file accepted for verification, 1 KB to 5 MB
 *                          ({@code SNIGF_ISSUANCE_MAX_UPLOAD_BYTES})
 * @param verifyPerMinute   public verifications per minute and client address, 1 to 600
 *                          ({@code SNIGF_RATE_VERIFY_PER_MINUTE})
 * @param maxTrackedClients client addresses whose limit is remembered ({@code SNIGF_RATE_MAX_TRACKED_CLIENTS})
 * @param pageSize          incidents per page, 5 to 200 ({@code SNIGF_ISSUANCE_PAGE_SIZE})
 */
@Validated
@ConfigurationProperties(prefix = "snigf.issuance")
public record IssuanceProperties(@NotNull URI verifyBaseUrl, @Min(1024) @Max(5_242_880) int maxUploadBytes,
                                 @Min(1) @Max(600) int verifyPerMinute, @Min(100) @Max(1_000_000) int maxTrackedClients,
                                 @Min(5) @Max(200) int pageSize) {

    public IssuanceProperties {
        if (verifyBaseUrl != null && !"https".equals(verifyBaseUrl.getScheme())) {
            throw new IllegalArgumentException("snigf.issuance.verify-base-url must be https");
        }
    }

    /** The page the QR code opens for a number: {@code <base>/#/verify/<number>}. */
    public String verifyUrl(String number) {
        return verifyBaseUrl.toString().replaceAll("/+$", "") + "/#/verify/" + number;
    }
}
