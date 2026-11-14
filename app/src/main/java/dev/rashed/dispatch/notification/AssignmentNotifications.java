package dev.rashed.dispatch.notification;

import dev.rashed.dispatch.workorder.WorkOrderAssigned;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/** Tells the technician they got the job. */
@Component
class AssignmentNotifications {

    private final Notifier notifier;

    AssignmentNotifications(Notifier notifier) {
        this.notifier = notifier;
    }

    // After commit, on another thread, in its own transaction. It reads nothing from the database, so the
    // missing tenant doesn't matter here. If sending fails, the event stays incomplete and is sent again.
    @ApplicationModuleListener
    void on(WorkOrderAssigned event) {
        notifier.notifyTechnician(event.technicianId(), "You got the job: " + event.title());
    }
}
