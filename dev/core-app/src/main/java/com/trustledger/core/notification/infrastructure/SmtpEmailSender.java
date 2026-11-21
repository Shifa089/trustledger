package com.trustledger.core.notification.infrastructure;

import com.trustledger.core.notification.application.NotificationProperties;
import com.trustledger.core.notification.domain.EmailMessage;
import com.trustledger.core.notification.domain.EmailSender;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * {@link EmailSender} over SMTP with Boot's {@link JavaMailSender} ({@code spring.mail.*}; Mailpit locally,
 * ADR 0017). Plain text only (a {@link SimpleMailMessage}): no HTML, so nothing to inject and nothing remote to load.
 */
@Component
class SmtpEmailSender implements EmailSender {

    private final JavaMailSender mailSender;
    private final String from;

    SmtpEmailSender(JavaMailSender mailSender, NotificationProperties properties) {
        this.mailSender = mailSender;
        this.from = properties.from();
    }

    @Override
    public void send(String to, EmailMessage message) {
        SimpleMailMessage mail = new SimpleMailMessage();
        mail.setFrom(from);
        mail.setTo(to);
        mail.setSubject(message.subject());
        mail.setText(message.body());
        mailSender.send(mail);
    }
}
