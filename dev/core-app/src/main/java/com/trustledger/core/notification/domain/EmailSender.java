package com.trustledger.core.notification.domain;

/** Port for sending one e-mail; implemented over SMTP ({@code JavaMailSender}) in {@code notification.infrastructure}. */
public interface EmailSender {

    /**
     * Hands the message to the mail server.
     *
     * @throws org.springframework.mail.MailException when the server can't be reached or refuses the message
     */
    void send(String to, EmailMessage message);
}
