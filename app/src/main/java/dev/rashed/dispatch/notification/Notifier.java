package dev.rashed.dispatch.notification;

import java.util.UUID;

/** Delivers a message to one person. Implementations: logging now; email and push later. */
public interface Notifier {

    void notifyTechnician(UUID technicianId, String message);
}
