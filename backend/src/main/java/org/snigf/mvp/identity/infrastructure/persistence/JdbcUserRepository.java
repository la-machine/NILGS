// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.identity.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.snigf.mvp.identity.AccountType;
import org.snigf.mvp.identity.RoleGrant;
import org.snigf.mvp.identity.domain.EmailAddress;
import org.snigf.mvp.identity.domain.LockoutPolicy;
import org.snigf.mvp.identity.domain.UserAccount;
import org.snigf.mvp.identity.domain.UserRepository;
import org.snigf.mvp.shared.id.UuidV7;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code app_user} and {@code role_assignment} as {@code snigf_app}, which may update only
 * the columns V1 grants. Counters change in one statement, so concurrent attempts on the
 * same account cannot lose a failure.
 */
@Repository
public class JdbcUserRepository implements UserRepository {

    private static final String COLUMNS = """
            id, email, display_name, password_hash, account_type, preferred_language, enabled,
            mfa_enrolled_at IS NOT NULL AS second_factor_ready, failed_logins, locked_until""";

    private final JdbcClient jdbc;

    public JdbcUserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<UserAccount> findByEmail(EmailAddress email) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM app_user WHERE lower(email) = :email")
                .param("email", email.value())
                .query((rs, n) -> account(rs))
                .optional();
    }

    @Override
    public Optional<UserAccount> findById(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM app_user WHERE id = :id")
                .param("id", id)
                .query((rs, n) -> account(rs))
                .optional();
    }

    @Override
    public Optional<UUID> createApplicant(UUID id, EmailAddress email, String displayName, String passwordHash,
                                          String preferredLanguage) {
        Optional<UUID> created = jdbc.sql("""
                        INSERT INTO app_user (id, email, display_name, password_hash, account_type, preferred_language)
                        VALUES (:id, :email, :name, :hash, 'APPLICANT', :lang)
                        ON CONFLICT ((lower(email))) DO NOTHING
                        RETURNING id""")
                .param("id", id)
                .param("email", email.value())
                .param("name", displayName)
                .param("hash", passwordHash)
                .param("lang", preferredLanguage)
                .query(UUID.class)
                .optional();
        created.ifPresent(user -> jdbc.sql("""
                        INSERT INTO role_assignment (id, user_id, role, grant_reason)
                        VALUES (:id, :user, 'APPLICANT', 'self-registration')""")
                .param("id", UuidV7.next())
                .param("user", user)
                .update());
        return created;
    }

    @Override
    public Failure recordFailedLogin(UUID id, LockoutPolicy policy, Instant now) {
        OffsetDateTime lockUntil = OffsetDateTime.ofInstant(policy.lockedUntil(now), ZoneOffset.UTC);
        return jdbc.sql("""
                        UPDATE app_user
                           SET failed_logins = CASE WHEN failed_logins + 1 >= :max THEN 0 ELSE failed_logins + 1 END,
                               locked_until  = CASE WHEN failed_logins + 1 >= :max THEN :lockUntil ELSE locked_until END,
                               version = version + 1
                         WHERE id = :id
                     RETURNING failed_logins, locked_until, locked_until = :lockUntil AS locked_now""")
                .param("max", policy.maxFailures())
                .param("lockUntil", lockUntil)
                .param("id", id)
                .query((rs, n) -> new Failure(rs.getInt("failed_logins"), instant(rs, "locked_until"),
                        rs.getBoolean("locked_now")))
                .single();
    }

    @Override
    public void recordSuccessfulLogin(UUID id) {
        jdbc.sql("UPDATE app_user SET failed_logins = 0, locked_until = NULL, version = version + 1 WHERE id = :id")
                .param("id", id)
                .update();
    }

    @Override
    public void updatePasswordHash(UUID id, String passwordHash) {
        jdbc.sql("UPDATE app_user SET password_hash = :hash, version = version + 1 WHERE id = :id")
                .param("hash", passwordHash)
                .param("id", id)
                .update();
    }

    @Override
    public Set<RoleGrant> activeGrants(UUID id) {
        return new LinkedHashSet<>(jdbc.sql("""
                        SELECT r.role, o.code FROM role_assignment r LEFT JOIN office o ON o.id = r.office_id
                         WHERE r.user_id = :id AND r.revoked_at IS NULL ORDER BY r.role, o.code""")
                .param("id", id)
                .query((rs, n) -> new RoleGrant(rs.getString(1), rs.getString(2)))
                .list());
    }

    private static UserAccount account(ResultSet rs) throws SQLException {
        return new UserAccount(
                rs.getObject("id", UUID.class),
                EmailAddress.of(rs.getString("email")),
                rs.getString("display_name"),
                rs.getString("password_hash"),
                AccountType.valueOf(rs.getString("account_type")),
                rs.getString("preferred_language"),
                rs.getBoolean("enabled"),
                rs.getBoolean("second_factor_ready"),
                rs.getInt("failed_logins"),
                instant(rs, "locked_until"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
