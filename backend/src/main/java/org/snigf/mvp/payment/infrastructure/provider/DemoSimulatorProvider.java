// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.payment.infrastructure.provider;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.naming.InvalidNameException;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;

import org.snigf.mvp.keys.KeyService;
import org.snigf.mvp.keys.KeyServiceException;
import org.snigf.mvp.keys.Mac;
import org.snigf.mvp.keys.MacKey;
import org.snigf.mvp.payment.domain.PaymentProvider;
import org.snigf.mvp.payment.domain.ProviderReport;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The DEMO payment simulator (ADR-037), the only approved provider. It never moves money.
 *
 * <p>Its callbacks (DEMO protocol v1) are authenticated in this order, and nothing of a rejected
 * one is read further:
 * <ol>
 *   <li>the provider's certificate subject, vouched for by the proxy's provider listener, names an
 *       allowed provider ({@code PROVIDER_NOT_RECOGNISED});</li>
 *   <li>{@code X-Snigf-Timestamp} (Unix seconds) and {@code X-Snigf-Signature}
 *       ({@code v<key version>=<base64>}) are present and well formed ({@code SIGNATURE_MISSING},
 *       {@code SIGNATURE_MALFORMED});</li>
 *   <li>the timestamp is within the allowed skew ({@code TIMESTAMP_OUT_OF_WINDOW});</li>
 *   <li>HMAC-SHA-256 of {@code <timestamp>.<raw body>} with the callback key, recomputed in key
 *       custody with the version named and compared in constant time ({@code SIGNATURE_INVALID}).</li>
 * </ol>
 * Only then is the body parsed; unknown fields, a missing {@code demo: true} or another provider
 * make it malformed.
 */
class DemoSimulatorProvider implements PaymentProvider {

    static final String TIMESTAMP_HEADER = "x-snigf-timestamp";
    static final String SIGNATURE_HEADER = "x-snigf-signature";
    /** The proxy's provider listener already refuses more. */
    static final int MAX_BODY_BYTES = 16 * 1024;
    private static final Pattern TIMESTAMP = Pattern.compile("^[0-9]{1,12}$");
    private static final Pattern SIGNATURE = Pattern.compile("^v([0-9]{1,6})=([A-Za-z0-9+/]{43}=)$");
    private static final Set<String> FIELDS = Set.of("provider", "transactionId", "reference", "amount", "currency",
            "status", "occurredAt", "demo");

    private final SimulatorChannel channel;
    private final KeyService keys;
    private final Set<String> allowedProviders;
    private final Duration maxSkew;
    private final Clock clock;
    private final JsonMapper json = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    DemoSimulatorProvider(SimulatorChannel channel, KeyService keys, Set<String> allowedProviders, Duration maxSkew,
                          Clock clock) {
        this.channel = channel;
        this.keys = keys;
        this.allowedProviders = Set.copyOf(allowedProviders);
        this.maxSkew = maxSkew;
        this.clock = clock;
    }

    @Override
    public Kind kind() {
        return Kind.DEMO_SIMULATOR;
    }

    @Override
    public String start(ChargeOrder order) {
        return channel.charge(order);
    }

    @Override
    public ProviderReport authenticate(Callback callback) {
        if (callback.body().length > MAX_BODY_BYTES) {
            throw new Rejected("BODY_TOO_LARGE");
        }
        if (commonName(callback.providerIdentity()).filter(allowedProviders::contains).isEmpty()) {
            throw new Rejected("PROVIDER_NOT_RECOGNISED");
        }
        String timestamp = callback.headers().get(TIMESTAMP_HEADER);
        String signature = callback.headers().get(SIGNATURE_HEADER);
        if (timestamp == null || signature == null) {
            throw new Rejected("SIGNATURE_MISSING");
        }
        Matcher sig = SIGNATURE.matcher(signature.strip());
        if (!TIMESTAMP.matcher(timestamp.strip()).matches() || !sig.matches()) {
            throw new Rejected("SIGNATURE_MALFORMED");
        }
        Instant sent = Instant.ofEpochSecond(Long.parseLong(timestamp.strip()));
        if (Duration.between(sent, clock.instant()).abs().compareTo(maxSkew) > 0) {
            throw new Rejected("TIMESTAMP_OUT_OF_WINDOW");
        }
        int keyVersion = Integer.parseInt(sig.group(1));
        if (keyVersion < 1) {
            throw new Rejected("SIGNATURE_MALFORMED");
        }
        byte[] body = callback.body();
        boolean valid;
        try {
            valid = keys.verifyMac(MacKey.PAYMENT_CALLBACK, signed(timestamp.strip(), body),
                    new Mac(keyVersion, Base64.getDecoder().decode(sig.group(2))));
        } catch (KeyServiceException e) {
            // Custody refusing (an unknown key version) is a wrong signature; custody unreachable
            // (the failure has a transport cause) is not the sender's fault.
            if (e.getCause() != null) {
                throw new Unavailable("key custody unavailable to verify a callback", e);
            }
            valid = false;
        }
        if (!valid) {
            throw new Rejected("SIGNATURE_INVALID");
        }
        return read(body, keyVersion);
    }

    /** The message signed: {@code <timestamp>.<raw body>}. */
    static byte[] signed(String timestamp, byte[] body) {
        byte[] prefix = (timestamp + ".").getBytes(StandardCharsets.US_ASCII);
        byte[] message = new byte[prefix.length + body.length];
        System.arraycopy(prefix, 0, message, 0, prefix.length);
        System.arraycopy(body, 0, message, prefix.length, body.length);
        return message;
    }

    private ProviderReport read(byte[] body, int keyVersion) {
        JsonNode node;
        try {
            node = json.readTree(body);
        } catch (JacksonException e) {
            throw new Malformed("not JSON");
        }
        if (node == null || !node.isObject()) {
            throw new Malformed("not an object");
        }
        for (String name : node.propertyNames()) {
            if (!FIELDS.contains(name)) {
                throw new Malformed("unknown field");
            }
        }
        if (!Kind.DEMO_SIMULATOR.name().equals(node.path("provider").asString(""))
                || !node.path("demo").isBoolean() || !node.path("demo").asBoolean()
                || !node.path("amount").isIntegralNumber() || !node.path("transactionId").isString()
                || !node.path("reference").isString() || !node.path("currency").isString()
                || !node.path("status").isString()) {
            throw new Malformed("missing or mistyped field");
        }
        try {
            ProviderReport.Status status = ProviderReport.Status.valueOf(node.path("status").asString());
            Instant occurredAt = node.hasNonNull("occurredAt") ? Instant.parse(node.path("occurredAt").asString()) : null;
            return new ProviderReport(Kind.DEMO_SIMULATOR, node.path("transactionId").asString(),
                    node.path("reference").asString(), node.path("amount").asLong(), node.path("currency").asString(),
                    status, occurredAt, keyVersion);
        } catch (IllegalArgumentException | DateTimeParseException e) {
            throw new Malformed("invalid field");
        }
    }

    /** The last CN of an RFC 2253 subject, as the proxy writes it. */
    static Optional<String> commonName(String subject) {
        if (subject == null || subject.isBlank() || subject.length() > 1024) {
            return Optional.empty();
        }
        try {
            return new LdapName(subject.strip()).getRdns().stream()
                    .filter(rdn -> "CN".equalsIgnoreCase(rdn.getType()))
                    .map(Rdn::getValue)
                    .map(Object::toString)
                    .reduce((first, second) -> second);
        } catch (InvalidNameException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
