package dev.rashed.dispatch.ai;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Saved model answers, one JSON file each, keyed by feature, prompt version and a hash of the exact prompt.
 * Evals re-run against saved answers instead of spending the daily quota, and tests replay them so CI never
 * calls the provider. A new prompt version or a different input is a different key, so a stale answer can't be
 * replayed for a changed prompt.
 */
@Component
class ReplayCache {

    private final AiProperties.Replay settings;
    private final JsonMapper json;

    ReplayCache(AiProperties ai, JsonMapper json) {
        this.settings = ai.replay();
        this.json = json;
    }

    boolean replaying() {
        return settings.mode() == AiProperties.ReplayMode.REPLAY;
    }

    Optional<ModelReply> find(String feature, String promptVersion, String prompt) {
        if (settings.mode() == AiProperties.ReplayMode.OFF) {
            return Optional.empty();
        }
        var file = file(feature, promptVersion, prompt);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(json.readValue(Files.readString(file), ModelReply.class));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    void save(String feature, String promptVersion, String prompt, ModelReply answer) {
        if (settings.mode() != AiProperties.ReplayMode.RECORD) {
            return;
        }
        var file = file(feature, promptVersion, prompt);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, json.writerWithDefaultPrettyPrinter().writeValueAsString(answer));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Path file(String feature, String promptVersion, String prompt) {
        return settings.directory().resolve(feature).resolve(promptVersion).resolve(sha256(prompt) + ".json");
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
