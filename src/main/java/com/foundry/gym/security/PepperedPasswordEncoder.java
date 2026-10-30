package com.foundry.gym.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * BCrypt with a secret salt that comes from the config server ({@code gym.security.password-salt}).
 *
 * <p>BCrypt already adds a random salt per password, stored inside each hash. This adds a second, secret one that is
 * <em>not</em> in the database (often called a pepper): the password is first run through HMAC-SHA256 with the secret,
 * and the result is what BCrypt hashes. A stolen database alone cannot be attacked offline without the secret. As a
 * bonus the HMAC output is always 44 characters, so the 72-byte BCrypt limit no longer cuts long passwords short.
 *
 * <p>Hashes made here start with {@code p1$}. Older hashes (plain BCrypt, from before the secret existed) still
 * verify, and {@link #upgradeEncoding} says they should be replaced, which the login does the first time that person
 * signs in.
 *
 * <p>Changing the secret later invalidates every {@code p1$} password: keep it and back it up.
 */
public class PepperedPasswordEncoder implements PasswordEncoder {

    static final String PREFIX = "p1$";

    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();
    private final SecretKeySpec secret;

    public PepperedPasswordEncoder(String salt) {
        this.secret = new SecretKeySpec(SecretKeys.require("gym.security.password-salt", salt).getBytes(StandardCharsets.UTF_8),
            "HmacSHA256");
    }

    @Override
    public String encode(CharSequence raw) {
        return PREFIX + bcrypt.encode(mix(raw));
    }

    @Override
    public boolean matches(CharSequence raw, String stored) {
        if (stored == null || stored.isEmpty()) {
            return false;
        }
        if (stored.startsWith(PREFIX)) {
            return bcrypt.matches(mix(raw), stored.substring(PREFIX.length()));
        }
        return bcrypt.matches(raw, stored); // made before the secret salt existed
    }

    /** True for a hash made without the secret salt: it should be re-made once the password is known. */
    @Override
    public boolean upgradeEncoding(String stored) {
        return stored == null || !stored.startsWith(PREFIX);
    }

    private String mix(CharSequence raw) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(secret);
            return Base64.getEncoder().encodeToString(mac.doFinal(raw.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not hash a password", e);
        }
    }
}
