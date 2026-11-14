package dev.rashed.dispatch.notification;

import java.util.UUID;

/** Delivers a message to one person or company. Implementations: logging now; email and push later. */
public interface Notifier {

    void notifyTechnician(UUID technicianId, String message);

    /** To the company's dispatchers (email later). */
    void notifyCompany(UUID tenantId, String message);
}
