package dev.rashed.dispatch.notification;

import dev.rashed.dispatch.workorder.WorkOrderSlaBreachedV1;
import dev.rashed.dispatch.workorder.WorkOrderStatus;
import dev.rashed.dispatch.workorder.WorkOrderStatusChangedV1;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

/** Turns a work order change into the email a company receives: an HTML and a plain-text version. */
@Component
class EmailTemplates {

    private static final String TEMPLATE = "email/work-order-update";

    // shortcut: every company is in Bangladesh for now; store a time zone per company when that changes.
    private static final ZoneId DHAKA = ZoneId.of("Asia/Dhaka");
    private static final DateTimeFormatter WAITING_SINCE =
            DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm 'Dhaka time'", Locale.ENGLISH);

    record Row(String label, String value, boolean mono) {}

    private final TemplateEngine engine;
    private final String webBaseUrl;

    EmailTemplates(TemplateEngine engine, @Value("${dispatch.web.base-url}") String webBaseUrl) {
        this.engine = engine;
        this.webBaseUrl = webBaseUrl;
    }

    /** @return the email, or null for an event the company isn't told about */
    EmailOutbox.@Nullable Email forCompany(
            WorkOrderEventReader.WorkOrderEvent event, String companyName, String recipient) {
        return switch (event) {
            case WorkOrderEventReader.StatusChange change -> statusChange(change.payload(), companyName, recipient);
            case WorkOrderEventReader.SlaBreach breach -> slaBreach(breach.payload(), companyName, recipient);
        };
    }

    private EmailOutbox.@Nullable Email statusChange(
            WorkOrderStatusChangedV1 change, String companyName, String recipient) {
        var title = titleOf(change.title(), change.workOrderId());
        var model = switch (change.to()) {
            case ASSIGNED ->
                model(
                        "A technician accepted " + title,
                        "Technician assigned",
                        "success",
                        "A technician accepted your work order",
                        "They're on it. You'll hear from us again when the work is done.",
                        "View work order");
            case SUBMITTED ->
                model(
                        "Ready for your approval: " + title,
                        "Waiting for approval",
                        "attention",
                        "The work is done and waiting for your approval",
                        "Check the photos and the checklist, then approve the work or send it back with a reason.",
                        "Review and approve");
            default -> null;
        };
        if (model == null) {
            return null;
        }
        model.put("title", title);
        return render(
                model, change.workOrderId(), new Row("Status", label(change.to()), false), companyName, recipient);
    }

    private EmailOutbox.Email slaBreach(WorkOrderSlaBreachedV1 breach, String companyName, String recipient) {
        var title = titleOf(breach.title(), breach.workOrderId());
        var model = model(
                "No technician yet: " + title,
                "No technician yet",
                "attention",
                "Nobody has taken this work order yet",
                "We offered it to technicians nearby, then farther away, and nobody accepted. A higher budget, a wider"
                        + " time window or a technician you know usually gets it moving.",
                "Open work order");
        model.put("title", title);
        var waiting = breach.waitingSince() == null
                ? "Unknown"
                : WAITING_SINCE.format(breach.waitingSince().atZone(DHAKA));
        return render(model, breach.workOrderId(), new Row("Waiting since", waiting, false), companyName, recipient);
    }

    private EmailOutbox.Email render(
            Map<String, Object> model, UUID workOrderId, Row middle, String companyName, String recipient) {
        model.putAll(Map.of(
                "rows",
                List.of(
                        new Row("Work order", (String) model.get("title"), false),
                        middle,
                        new Row("Reference", workOrderId.toString(), true)),
                "ctaUrl",
                webBaseUrl + "/work-orders/" + workOrderId,
                "companyName",
                companyName,
                "recipient",
                recipient,
                "preheader",
                model.get("intro")));
        var context = new Context();
        context.setVariables(model);
        return new EmailOutbox.Email(
                recipient,
                (String) model.get("subject"),
                engine.process(TEMPLATE + ".txt", context),
                engine.process(TEMPLATE, context));
    }

    private static String titleOf(@Nullable String title, UUID workOrderId) {
        return title == null ? "Work order " + workOrderId : title;
    }

    private static Map<String, Object> model(
            String subject, String statusLabel, String tone, String headline, String intro, String ctaLabel) {
        return new HashMap<>(Map.of(
                "subject", subject,
                "statusLabel", statusLabel,
                "tone", tone,
                "headline", headline,
                "intro", intro,
                "ctaLabel", ctaLabel));
    }

    private static String label(WorkOrderStatus status) {
        var name = status.name().replace('_', ' ').toLowerCase();
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    /** Plain-text templates (*.txt) next to the HTML ones, which Spring Boot's own resolver handles. */
    @Configuration
    static class TextTemplates {

        @Bean
        ClassLoaderTemplateResolver textTemplateResolver() {
            var resolver = new ClassLoaderTemplateResolver();
            resolver.setPrefix("templates/");
            resolver.setTemplateMode(TemplateMode.TEXT);
            resolver.setResolvablePatterns(Set.of("*.txt"));
            resolver.setCharacterEncoding("UTF-8");
            resolver.setOrder(1); // before Boot's HTML resolver, which would claim every name
            resolver.setCheckExistence(true);
            return resolver;
        }
    }
}
