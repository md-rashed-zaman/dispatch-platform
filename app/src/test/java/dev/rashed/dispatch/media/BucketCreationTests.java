package dev.rashed.dispatch.media;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rashed.dispatch.TestcontainersConfiguration;
import java.net.URI;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

/**
 * Several instances starting at once (a Kubernetes Deployment's pods do) all find the bucket missing and all create
 * it: every one must start, not only the first (step 5.2 found the second pod crashing on this).
 */
class BucketCreationTests {

    @Test
    void instancesStartingTogetherAllStart() {
        var seaweed = TestcontainersConfiguration.SEAWEEDFS;
        seaweed.start();
        var storage = new StorageProperties(
                URI.create("http://" + seaweed.getHost() + ":" + seaweed.getMappedPort(8333)),
                "us-east-1",
                "race-" + UUID.randomUUID(),
                "dispatch",
                "dispatch-secret",
                true);
        var config = new StorageConfig();
        var start = new CountDownLatch(1);
        var instances = new ArrayList<CompletableFuture<Void>>();
        for (int i = 0; i < 8; i++) {
            var s3 = config.s3Client(storage);
            var runner = config.createBucketIfMissing(s3, storage);
            instances.add(CompletableFuture.runAsync(() -> {
                try {
                    start.await();
                    runner.run(new DefaultApplicationArguments());
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                } finally {
                    s3.close();
                }
            }));
        }
        start.countDown();

        assertThat(instances)
                .allSatisfy(instance -> assertThat(instance).succeedsWithin(java.time.Duration.ofSeconds(30)));
    }
}
