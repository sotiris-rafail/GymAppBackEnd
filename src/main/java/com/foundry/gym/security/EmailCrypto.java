package com.foundry.gym.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Encrypts email addresses with a key that comes from the config server ({@code gym.security.email-key}).
 *
 * <p>Two different values are kept for every address:
 * <ul>
 *   <li>the address itself, encrypted with AES-256-GCM and a fresh random IV each time, stored as
 *       {@code v1:<base64 of iv + ciphertext>}. Only someone with the key can read it, and GCM notices tampering;</li>
 *   <li>a "blind index": HMAC-SHA256 of the lower-case address, as hex. Because the ciphertext is different every
 *       time it cannot be searched or made unique, so logins and the "already registered" check look the address up
 *       by this index instead. It reveals nothing without the key.</li>
 * </ul>
 * Both sub-keys are derived from the one configured key, so a single secret is enough.
 */
@Component
public class EmailCrypto {

    static final String PREFIX = "v1:";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec encryptionKey;
    private final SecretKeySpec indexKey;
    private final SecureRandom random = new SecureRandom();

    public EmailCrypto(@Value("${gym.security.email-key:}") String key) {
        String k = SecretKeys.require("gym.security.email-key", key);
        byte[] master = k.getBytes(StandardCharsets.UTF_8);
        this.encryptionKey = new SecretKeySpec(derive(master, "foundry-email-encryption"), "AES");
        this.indexKey = new SecretKeySpec(derive(master, "foundry-email-index"), "HmacSHA256");
    }

    /** Lower-case, trimmed: the one form an address is stored and searched in. */
    public static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    public boolean isEncrypted(String stored) {
        return stored != null && stored.startsWith(PREFIX);
    }

    public String encrypt(String plain) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, new GCMParameterSpec(TAG_BITS, iv));
            byte[] sealed = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + sealed.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(sealed, 0, out, iv.length, sealed.length);
            return PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not encrypt an email address", e);
        }
    }

    public String decrypt(String stored) {
        try {
            byte[] in = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey, new GCMParameterSpec(TAG_BITS, in, 0, IV_BYTES));
            return new String(cipher.doFinal(in, IV_BYTES, in.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException | IndexOutOfBoundsException e) {
            throw new IllegalStateException("Could not decrypt an email address. Is gym.security.email-key the same key "
                + "the addresses were encrypted with?", e);
        }
    }

    /** The searchable fingerprint of an address (normalised first). */
    public String index(String email) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(indexKey);
            return HexFormat.of().formatHex(mac.doFinal(normalize(email).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not index an email address", e);
        }
    }

    private static byte[] derive(byte[] master, String label) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(master, "HmacSHA256"));
            return mac.doFinal(label.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
