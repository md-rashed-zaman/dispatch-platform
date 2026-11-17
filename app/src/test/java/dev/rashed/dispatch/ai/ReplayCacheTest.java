package dev.rashed.dispatch.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class ReplayCacheTest {

    @TempDir
    Path directory;

    final JsonMapper json = JsonMapper.builder().build();
    final ModelReply answer = new ModelReply("gemini-flash-lite-latest", "{\"skills\":[\"cctv\"]}", 120, 8);

    @Test
    void recordsAnAnswerAndReplaysItForTheSamePromptOnly() {
        cache(AiProperties.ReplayMode.RECORD).save("skill-suggestions", "v1", "prompt A", answer);

        var replay = cache(AiProperties.ReplayMode.REPLAY);
        assertThat(replay.find("skill-suggestions", "v1", "prompt A")).contains(answer);
        assertThat(replay.find("skill-suggestions", "v1", "prompt B")).isEmpty(); // different input
        assertThat(replay.find("skill-suggestions", "v2", "prompt A")).isEmpty(); // new prompt version
    }

    @Test
    void offNeitherSavesNorReads() {
        var off = cache(AiProperties.ReplayMode.OFF);
        off.save("skill-suggestions", "v1", "prompt A", answer);

        assertThat(directory).isEmptyDirectory();
        assertThat(off.find("skill-suggestions", "v1", "prompt A")).isEmpty();
    }

    @Test
    void replayModeNeverSaves() {
        cache(AiProperties.ReplayMode.REPLAY).save("skill-suggestions", "v1", "prompt A", answer);

        assertThat(directory).isEmptyDirectory();
    }

    private ReplayCache cache(AiProperties.ReplayMode mode) {
        var properties = new AiProperties(
                Duration.ofSeconds(10),
                Duration.ofSeconds(20),
                2,
                Duration.ofSeconds(1),
                100,
                Map.of(),
                Map.of(),
                new AiProperties.Replay(mode, directory));
        return new ReplayCache(properties, json);
    }
}
