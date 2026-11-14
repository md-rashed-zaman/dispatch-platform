package dev.rashed.dispatch.notification;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

// shortcut: writes to the log instead of reaching anyone; email or push replaces it when there are users.
@Component
class LoggingNotifier implements Notifier {

    private static final Logger log = LoggerFactory.getLogger(LoggingNotifier.class);

    @Override
    public void notifyTechnician(UUID technicianId, String message) {
        log.info("To technician {}: {}", technicianId, message);
    }
}
