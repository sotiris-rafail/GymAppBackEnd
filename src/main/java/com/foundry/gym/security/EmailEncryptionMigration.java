package com.foundry.gym.security;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Encrypts the addresses of accounts made before encryption existed (their blind index is still empty).
 * Runs at every start, before anything else uses the accounts, and does nothing once every row is done.
 * Uses plain SQL on purpose: going through the entity would try to read the old plain text as ciphertext.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class EmailEncryptionMigration implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(EmailEncryptionMigration.class);

    private final JdbcTemplate jdbc;
    private final EmailCrypto crypto;

    public EmailEncryptionMigration(JdbcTemplate jdbc, EmailCrypto crypto) {
        this.jdbc = jdbc;
        this.crypto = crypto;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        List<Map<String, Object>> rows = jdbc.queryForList("select id, email from app_user where email_hash is null");
        for (Map<String, Object> row : rows) {
            String stored = (String) row.get("email");
            String plain = EmailCrypto.normalize(crypto.isEncrypted(stored) ? crypto.decrypt(stored) : stored);
            jdbc.update("update app_user set email = ?, email_hash = ? where id = ?",
                crypto.encrypt(plain), crypto.index(plain), row.get("id"));
        }
        if (!rows.isEmpty()) {
            log.info("Encrypted the email address of {} existing account(s)", rows.size());
        }
    }
}
