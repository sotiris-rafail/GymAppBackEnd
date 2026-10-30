package com.foundry.gym.mail;

import java.util.Properties;

import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

/**
 * Sends through the mail server in the live {@link MailSettings} (host, port, user, password, sender). The connection settings
 * are rebuilt whenever they change on the config server, so a new password or server is used by the very next email.
 */
@Component
public class SmtpEmailGateway implements EmailGateway {

    private final MailSettings settings;
    private MailSettings.Snapshot builtFor;
    private JavaMailSenderImpl sender;

    public SmtpEmailGateway(MailSettings settings) {
        this.settings = settings;
    }

    @Override
    public void send(String to, String subject, String body) {
        MailSettings.Snapshot s = settings.current();
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(s.from());
        message.setTo(to);
        message.setSubject(subject);
        message.setText(body);
        senderFor(s).send(message);
    }

    /** The mail sender for the given settings; made again only when they are not the ones it was made for. */
    synchronized JavaMailSender senderFor(MailSettings.Snapshot s) {
        if (sender == null || !s.equals(builtFor)) {
            JavaMailSenderImpl made = new JavaMailSenderImpl();
            made.setHost(s.host());
            made.setPort(s.port());
            made.setUsername(s.username().isEmpty() ? null : s.username());
            made.setPassword(s.password().isEmpty() ? null : s.password());
            made.setDefaultEncoding("UTF-8");
            Properties p = new Properties();
            s.javaMail().forEach(p::setProperty);
            made.setJavaMailProperties(p);
            sender = made;
            builtFor = s;
        }
        return sender;
    }
}
