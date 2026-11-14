package dev.rashed.dispatch.notification;

import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import dev.rashed.dispatch.TestcontainersConfiguration;
import dev.rashed.dispatch.workorder.WorkOrderAssigned;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class AssignmentNotificationsTests {

    @MockitoBean
    Notifier notifier;

    @Autowired
    ApplicationEventPublisher events;

    @Autowired
    TransactionTemplate transaction;

    @Autowired
    IncompleteEventPublications incomplete;

    @Test
    void theTechnicianHearsTheyGotTheJob() {
        var technician = UUID.randomUUID();

        publishAndCommit(
                new WorkOrderAssigned(UUID.randomUUID(), UUID.randomUUID(), technician, "Router down", Instant.now()));

        verify(notifier, timeout(5_000)).notifyTechnician(technician, "You got the job: Router down");
    }

    /**
     * The reason for after-commit listeners. Routing could re-check the database, but a message, once sent,
     * can't be taken back: nobody may hear about an assignment that rolled back.
     */
    @Test
    void aRolledBackAssignmentNotifiesNobody() {
        var technician = UUID.randomUUID();

        try {
            transaction.executeWithoutResult(status -> {
                events.publishEvent(new WorkOrderAssigned(
                        UUID.randomUUID(), UUID.randomUUID(), technician, "Router down", Instant.now()));
                throw new IllegalStateException("something later in the same transaction fails");
            });
        } catch (IllegalStateException expected) {
            // rolled back
        }

        verify(notifier, after(2_000).never()).notifyTechnician(eq(technician), anyString());
    }

    /** "What happens to an event if the listener throws?" It isn't lost: it waits, and is delivered again. */
    @Test
    void aFailedNotificationIsKeptAndSentAgain() {
        var technician = UUID.randomUUID();
        doThrow(new IllegalStateException("SMS provider down"))
                .doNothing()
                .when(notifier)
                .notifyTechnician(eq(technician), anyString());

        publishAndCommit(
                new WorkOrderAssigned(UUID.randomUUID(), UUID.randomUUID(), technician, "Router down", Instant.now()));
        verify(notifier, timeout(5_000)).notifyTechnician(eq(technician), anyString()); // first try fails

        // What a restart (republish-outstanding-events-on-restart) or an ops job does with incomplete events.
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            incomplete.resubmitIncompletePublications(
                    publication -> publication.getEvent() instanceof WorkOrderAssigned a
                            && a.technicianId().equals(technician));
            verify(notifier, times(2)).notifyTechnician(eq(technician), anyString());
        });
        doNothing().when(notifier).notifyTechnician(eq(technician), anyString());
    }

    private void publishAndCommit(Object event) {
        transaction.executeWithoutResult(status -> events.publishEvent(event));
    }
}
