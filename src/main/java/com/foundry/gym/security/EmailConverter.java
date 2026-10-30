package com.foundry.gym.security;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import org.springframework.stereotype.Component;

/** Encrypts an email address on its way into the database and decrypts it on the way out. The rest of the code sees plain text. */
@Converter
@Component
public class EmailConverter implements AttributeConverter<String, String> {

    private final EmailCrypto crypto;

    public EmailConverter(EmailCrypto crypto) {
        this.crypto = crypto;
    }

    @Override
    public String convertToDatabaseColumn(String email) {
        return email == null ? null : crypto.encrypt(email);
    }

    @Override
    public String convertToEntityAttribute(String stored) {
        // An address not yet encrypted (EmailEncryptionMigration fixes those at start-up) is passed through as it is.
        return stored == null ? null : crypto.isEncrypted(stored) ? crypto.decrypt(stored) : stored;
    }
}
