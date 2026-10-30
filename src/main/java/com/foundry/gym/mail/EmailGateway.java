package com.foundry.gym.mail;

/** Hands one email to a mail server. Throws if it could not be handed over (the sender then retries later). */
public interface EmailGateway {

    void send(String to, String subject, String body);
}
