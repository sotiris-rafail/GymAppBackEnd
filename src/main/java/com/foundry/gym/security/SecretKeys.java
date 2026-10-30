package com.foundry.gym.security;

/** Checks the secrets that come from the config server. They are never given a default: a guessable key is no key. */
final class SecretKeys {

    static final int MIN_LENGTH = 16;

    private SecretKeys() {
    }

    static String require(String property, String value) {
        String v = value == null ? "" : value.trim();
        if (v.length() < MIN_LENGTH) {
            throw new IllegalStateException("The setting '" + property + "' is missing or shorter than " + MIN_LENGTH
                + " characters. Set it on the config server (app 'backend') or with the environment variable "
                + property.toUpperCase().replace('.', '_').replace('-', '_')
                + ". Make one with: openssl rand -base64 32");
        }
        return v;
    }
}
