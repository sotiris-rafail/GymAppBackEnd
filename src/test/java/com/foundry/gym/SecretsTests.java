package com.foundry.gym;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;

import com.foundry.gym.security.EmailCrypto;
import com.foundry.gym.security.EmailEncryptionMigration;
import com.foundry.gym.security.PepperedPasswordEncoder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

/** The secret password salt and the email encryption key (both from the config server). */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:secretstest;DB_CLOSE_DELAY=-1",
    "gym.seed-demo-data=false",
    "config.client.enabled=false",
    "gym.security.password-salt=test-password-salt-0123456789",
    "gym.security.email-key=test-email-key-0123456789abcdef"
})
@AutoConfigureMockMvc
class SecretsTests {

    private static final String SALT = "test-password-salt-0123456789";
    private static final String KEY = "test-email-key-0123456789abcdef";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired EmailEncryptionMigration migration;

    // ---- the pieces on their own -------------------------------------------------------------------------------

    @Test
    void passwords_are_hashed_with_the_secret_salt_and_old_hashes_still_work() {
        PepperedPasswordEncoder enc = new PepperedPasswordEncoder(SALT);
        String hash = enc.encode("pass1234");
        assertThat(hash).startsWith("p1$");
        assertThat(enc.matches("pass1234", hash)).isTrue();
        assertThat(enc.matches("pass12345", hash)).isFalse();
        assertThat(enc.upgradeEncoding(hash)).isFalse();
        // the same password gives a different hash each time (BCrypt's own salt)
        assertThat(enc.encode("pass1234")).isNotEqualTo(hash);
        // another secret salt cannot verify it: a stolen database alone is not enough
        assertThat(new PepperedPasswordEncoder("another-password-salt-123456").matches("pass1234", hash)).isFalse();
        // a plain BCrypt hash from before the secret existed still verifies, and is flagged for an upgrade
        String legacy = new BCryptPasswordEncoder().encode("pass1234");
        assertThat(enc.matches("pass1234", legacy)).isTrue();
        assertThat(enc.upgradeEncoding(legacy)).isTrue();
        // passwords longer than BCrypt's 72 bytes are no longer cut short
        String a = "x".repeat(80) + "A", b = "x".repeat(80) + "B";
        assertThat(enc.matches(b, enc.encode(a))).isFalse();
    }

    @Test
    void a_missing_or_short_secret_stops_the_backend_from_starting() {
        assertThatThrownBy(() -> new PepperedPasswordEncoder("")).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("gym.security.password-salt");
        assertThatThrownBy(() -> new EmailCrypto("short")).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("gym.security.email-key");
    }

    @Test
    void emails_round_trip_are_different_every_time_and_need_the_right_key() {
        EmailCrypto crypto = new EmailCrypto(KEY);
        String one = crypto.encrypt("alex@demo.gym"), two = crypto.encrypt("alex@demo.gym");
        assertThat(one).startsWith("v1:").doesNotContain("alex").isNotEqualTo(two);
        assertThat(crypto.decrypt(one)).isEqualTo("alex@demo.gym");
        assertThat(crypto.decrypt(two)).isEqualTo("alex@demo.gym");
        assertThat(crypto.decrypt(crypto.encrypt("Ünïcode+tag@exämple.gym"))).isEqualTo("Ünïcode+tag@exämple.gym");
        assertThatThrownBy(() -> new EmailCrypto("a-completely-different-key-xyz").decrypt(one))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("gym.security.email-key");
        // a changed ciphertext is refused, not read as something else
        String tampered = one.substring(0, one.length() - 3) + (one.endsWith("AAA") ? "BBB" : "AAA");
        assertThatThrownBy(() -> crypto.decrypt(tampered)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void the_search_index_ignores_case_and_spaces_and_depends_on_the_key() {
        EmailCrypto crypto = new EmailCrypto(KEY);
        assertThat(crypto.index("  Alex@Demo.GYM ")).isEqualTo(crypto.index("alex@demo.gym")).hasSize(64);
        assertThat(crypto.index("alex@demo.gym")).isNotEqualTo(crypto.index("sam@demo.gym"));
        assertThat(new EmailCrypto("a-completely-different-key-xyz").index("alex@demo.gym")).isNotEqualTo(crypto.index("alex@demo.gym"));
    }

    // ---- in the running application ----------------------------------------------------------------------------

    private void register(String email, String password) throws Exception {
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Test Member\",\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
            .andExpect(status().isOk());
    }

    private int login(String email, String password) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\",\"role\":\"MEMBER\"}"))
            .andReturn().getResponse().getStatus();
    }

    @Test
    void the_database_holds_only_the_encrypted_email_and_the_salted_hash_and_login_still_works() throws Exception {
        String email = UUID.randomUUID() + "@test.gym";
        register(email, "pass1234");
        Map<String, Object> row = jdbc.queryForMap("select email, email_hash, password_hash from app_user where email_hash = ?",
            new EmailCrypto(KEY).index(email));
        assertThat((String) row.get("email")).startsWith("v1:").doesNotContain("@test.gym");
        assertThat((String) row.get("email_hash")).hasSize(64);
        assertThat((String) row.get("password_hash")).startsWith("p1$");
        // logging in works with any capitalisation, and the same address cannot register twice
        assertThat(login(email.toUpperCase(), "pass1234")).isEqualTo(200);
        assertThat(login(email, "wrong-pass")).isEqualTo(401);
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Again\",\"email\":\"" + email.toUpperCase() + "\",\"password\":\"pass1234\"}"))
            .andExpect(status().isConflict());
    }

    @Test
    void accounts_from_before_the_keys_existed_are_encrypted_at_start_and_their_password_is_upgraded_at_login() throws Exception {
        String email = UUID.randomUUID() + "@old.gym";
        jdbc.update("insert into app_user (name, email, password_hash, role) values (?, ?, ?, 'MEMBER')",
            "Old Member", email, new BCryptPasswordEncoder().encode("oldpass1"));
        assertThat(jdbc.queryForObject("select email_hash from app_user where email = ?", String.class, email)).isNull();

        migration.run(null);

        assertThat(jdbc.queryForObject("select count(*) from app_user where email = ?", Integer.class, email)).isZero();
        Map<String, Object> row = jdbc.queryForMap("select email, password_hash from app_user where email_hash = ?",
            new EmailCrypto(KEY).index(email));
        assertThat((String) row.get("email")).startsWith("v1:");
        assertThat((String) row.get("password_hash")).startsWith("$2"); // still the old kind
        assertThat(login(email, "oldpass1")).isEqualTo(200);
        assertThat(jdbc.queryForObject("select password_hash from app_user where email_hash = ?", String.class,
            new EmailCrypto(KEY).index(email))).startsWith("p1$"); // replaced by the login
        assertThat(login(email, "oldpass1")).isEqualTo(200);       // and still works
    }
}
