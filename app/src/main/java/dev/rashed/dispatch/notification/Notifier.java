package dev.rashed.dispatch.notification;

import java.util.UUID;

/** Delivers a message to one technician. Implementations: logging now; push or SMS later. */
public interface Notifier {

    void notifyTechnician(UUID technicianId, String message);
}
