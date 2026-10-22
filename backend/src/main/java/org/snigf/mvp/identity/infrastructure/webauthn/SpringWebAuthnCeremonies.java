// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.identity.infrastructure.webauthn;

import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.snigf.mvp.identity.application.IdentityProperties;
import org.snigf.mvp.identity.application.WebAuthnCeremonies;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.webauthn.api.AttestationConveyancePreference;
import org.springframework.security.web.webauthn.api.AuthenticatorAssertionResponse;
import org.springframework.security.web.webauthn.api.AuthenticatorSelectionCriteria;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.CredentialRecord;
import org.springframework.security.web.webauthn.api.ImmutablePublicKeyCredentialUserEntity;
import org.springframework.security.web.webauthn.api.PublicKeyCredential;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialCreationOptions;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialRequestOptions;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialRpEntity;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialUserEntity;
import org.springframework.security.web.webauthn.api.ResidentKeyRequirement;
import org.springframework.security.web.webauthn.api.UserVerificationRequirement;
import org.springframework.security.web.webauthn.jackson.WebauthnJacksonModule;
import org.springframework.security.web.webauthn.management.ImmutablePublicKeyCredentialCreationOptionsRequest;
import org.springframework.security.web.webauthn.management.ImmutablePublicKeyCredentialRequestOptionsRequest;
import org.springframework.security.web.webauthn.management.ImmutableRelyingPartyRegistrationRequest;
import org.springframework.security.web.webauthn.management.JdbcPublicKeyCredentialUserEntityRepository;
import org.springframework.security.web.webauthn.management.JdbcUserCredentialRepository;
import org.springframework.security.web.webauthn.management.RelyingPartyAuthenticationRequest;
import org.springframework.security.web.webauthn.management.RelyingPartyPublicKey;
import org.springframework.security.web.webauthn.management.Webauthn4JRelyingPartyOperations;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The WebAuthn relying party, on Spring Security's {@link Webauthn4JRelyingPartyOperations}
 * (verification by webauthn4j) and its JDBC repositories (tables of V6).
 *
 * <p>Policy applied to every ceremony:
 * <ul>
 *   <li>user verification <b>required</b> (PIN or biometric on the authenticator), at
 *       registration and at every assertion;</li>
 *   <li>non-discoverable credentials (a second factor, not a username replacement);</li>
 *   <li>attestation as configured ({@code none} by default: no device trust list yet);</li>
 *   <li>only the configured relying-party id and exact origins;</li>
 *   <li>at assertion, only the account's own credentials are allowed, and the credential's
 *       owner must be the account proving it.</li>
 * </ul>
 * The WebAuthn user name is the account id (stable, no personal data); the display name
 * is the account's display name.
 */
@Component
public class SpringWebAuthnCeremonies implements WebAuthnCeremonies {

    private static final Pattern LABEL = Pattern.compile("[\\p{L}\\p{N} ._'()-]{1,64}");
    private static final int MAX_RESPONSE_CHARS = 32_768;

    private final Webauthn4JRelyingPartyOperations relyingParty;
    private final JdbcPublicKeyCredentialUserEntityRepository userEntities;
    private final JdbcUserCredentialRepository credentials;
    private final CeremonyStore ceremonies;
    private final JsonMapper json;

    public SpringWebAuthnCeremonies(JdbcOperations jdbc, IdentityProperties properties, Clock clock) {
        IdentityProperties.WebAuthn settings = properties.webauthn();
        this.userEntities = new JdbcPublicKeyCredentialUserEntityRepository(jdbc);
        this.credentials = new JdbcUserCredentialRepository(jdbc);
        this.relyingParty = new Webauthn4JRelyingPartyOperations(userEntities, credentials,
                PublicKeyCredentialRpEntity.builder().id(settings.rpId()).name(settings.rpName()).build(),
                Set.copyOf(settings.allowedOrigins()));
        AttestationConveyancePreference attestation = switch (settings.attestation()) {
            case "direct" -> AttestationConveyancePreference.DIRECT;
            case "indirect" -> AttestationConveyancePreference.INDIRECT;
            default -> AttestationConveyancePreference.NONE;
        };
        relyingParty.setCustomizeCreationOptions(options -> options
                .timeout(settings.timeout())
                .attestation(attestation)
                .authenticatorSelection(AuthenticatorSelectionCriteria.builder()
                        .userVerification(UserVerificationRequirement.REQUIRED)
                        .residentKey(ResidentKeyRequirement.DISCOURAGED)
                        .build()));
        relyingParty.setCustomizeRequestOptions(options -> options
                .timeout(settings.timeout())
                .userVerification(UserVerificationRequirement.REQUIRED));
        this.ceremonies = new CeremonyStore(settings.timeout(), 10_000, clock);
        this.json = JsonMapper.builder().addModule(new WebauthnJacksonModule()).build();
    }

    @Override
    public Started beginRegistration(UUID userId, String displayName) {
        PublicKeyCredentialUserEntity entity = userEntities.findByUsername(userId.toString());
        if (entity == null) {
            userEntities.save(ImmutablePublicKeyCredentialUserEntity.builder()
                    .id(Bytes.random()).name(userId.toString()).displayName(displayName).build());
        }
        PublicKeyCredentialCreationOptions options = relyingParty.createPublicKeyCredentialCreationOptions(
                new ImmutablePublicKeyCredentialCreationOptionsRequest(as(userId)));
        String id = ceremonies.put(CeremonyStore.Kind.REGISTRATION, userId, options);
        return new Started(id, json.writeValueAsString(options));
    }

    @Override
    public Registered finishRegistration(String ceremonyId, UUID userId, String responseJson, String label) {
        if (label == null || !LABEL.matcher(label.strip()).matches()) {
            throw new Rejected("the authenticator label must have 1 to 64 letters, digits, spaces or . _ ' ( ) -");
        }
        PublicKeyCredentialCreationOptions options = ceremonies
                .take(ceremonyId, CeremonyStore.Kind.REGISTRATION, userId, PublicKeyCredentialCreationOptions.class)
                .orElseThrow(() -> new Rejected("no matching registration in progress"));
        PublicKeyCredential<org.springframework.security.web.webauthn.api.AuthenticatorAttestationResponse> credential =
                parse(responseJson, new TypeReference<>() { });
        try {
            CredentialRecord record = relyingParty.registerCredential(new ImmutableRelyingPartyRegistrationRequest(
                    options, new RelyingPartyPublicKey(credential, label.strip())));
            return new Registered(record.getCredentialId().toBase64UrlString(), record.getLabel());
        } catch (RuntimeException e) {
            throw new Rejected("the registration did not verify", e);
        }
    }

    @Override
    public Started beginAssertion(UUID userId) {
        PublicKeyCredentialRequestOptions options = relyingParty.createCredentialRequestOptions(
                new ImmutablePublicKeyCredentialRequestOptionsRequest(as(userId)));
        if (options.getAllowCredentials() == null || options.getAllowCredentials().isEmpty()) {
            throw new Rejected("the account has no authenticator");
        }
        String id = ceremonies.put(CeremonyStore.Kind.ASSERTION, userId, options);
        return new Started(id, json.writeValueAsString(options));
    }

    @Override
    public void finishAssertion(String ceremonyId, UUID userId, String responseJson) {
        PublicKeyCredentialRequestOptions options = ceremonies
                .take(ceremonyId, CeremonyStore.Kind.ASSERTION, userId, PublicKeyCredentialRequestOptions.class)
                .orElseThrow(() -> new Rejected("no matching verification in progress"));
        PublicKeyCredential<AuthenticatorAssertionResponse> credential = parse(responseJson, new TypeReference<>() { });
        PublicKeyCredentialUserEntity owner;
        try {
            owner = relyingParty.authenticate(new RelyingPartyAuthenticationRequest(options, credential));
        } catch (RuntimeException e) {
            throw new Rejected("the assertion did not verify", e);
        }
        if (owner == null || !userId.toString().equals(owner.getName())) {
            throw new Rejected("the credential belongs to another account");
        }
    }

    @Override
    public int countCredentials(UUID userId) {
        PublicKeyCredentialUserEntity entity = userEntities.findByUsername(userId.toString());
        if (entity == null) {
            return 0;
        }
        List<CredentialRecord> records = credentials.findByUserId(entity.getId());
        return records.size();
    }

    private <T> T parse(String responseJson, TypeReference<T> type) {
        if (responseJson == null || responseJson.length() > MAX_RESPONSE_CHARS) {
            throw new Rejected("the authenticator response is missing or too large");
        }
        try {
            return json.readValue(responseJson, type);
        } catch (RuntimeException e) {
            throw new Rejected("the authenticator response is not valid WebAuthn JSON", e);
        }
    }

    /** The relying party names users by the account id, never by e-mail or display name. */
    private static Authentication as(UUID userId) {
        return UsernamePasswordAuthenticationToken.authenticated(userId.toString(), null, List.of());
    }
}
