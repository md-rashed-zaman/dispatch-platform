package dev.rashed.dispatch.notification;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Emails to send. Queued in the caller's transaction, delivered by {@link EmailSender}. Nothing here talks
 * to the mail server, so it's never slow and never fails because the mail server is down.
 */
@Component
class EmailOutbox {

    record Email(String recipient, String subject, String text, String html) {}

    private final JdbcClient jdbc;

    EmailOutbox(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param messageId what the email is about (the event id): queueing the same one twice does nothing
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void queue(UUID messageId, Email email) {
        jdbc.sql("""
                        insert into outgoing_email (message_id, recipient, subject, text_body, html_body)
                        values (:messageId, :recipient, :subject, :text, :html)
                        on conflict (message_id) do nothing
                        """)
                .param("messageId", messageId)
                .param("recipient", email.recipient())
                .param("subject", email.subject())
                .param("text", email.text())
                .param("html", email.html())
                .update();
    }
}
