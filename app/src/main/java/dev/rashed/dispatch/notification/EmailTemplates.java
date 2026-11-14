package dev.rashed.dispatch.notification;

import dev.rashed.dispatch.workorder.WorkOrderStatus;
import dev.rashed.dispatch.workorder.WorkOrderStatusChangedV1;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    record Row(String label, String value, boolean mono) {}

    private final TemplateEngine engine;
    private final String webBaseUrl;

    EmailTemplates(TemplateEngine engine, @Value("${dispatch.web.base-url}") String webBaseUrl) {
        this.engine = engine;
        this.webBaseUrl = webBaseUrl;
    }

    /** @return the email, or null for a change the company isn't told about */
    EmailOutbox.@Nullable Email forCompany(WorkOrderStatusChangedV1 change, String companyName, String recipient) {
        var title = change.title() == null ? "Work order " + change.workOrderId() : change.title();
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
        model.putAll(Map.of(
                "rows",
                List.of(
                        new Row("Work order", title, false),
                        new Row("Status", label(change.to()), false),
                        new Row("Reference", change.workOrderId().toString(), true)),
                "ctaUrl",
                webBaseUrl + "/work-orders/" + change.workOrderId(),
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
