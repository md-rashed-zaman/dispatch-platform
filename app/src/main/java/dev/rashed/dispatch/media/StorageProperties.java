package dev.rashed.dispatch.media;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where attachments are stored. Only these values differ between SeaweedFS (local, tests) and Amazon S3.
 *
 * @param createBucket create the bucket at startup if it's missing: local stacks and tests only
 */
@ConfigurationProperties("dispatch.storage")
record StorageProperties(
        URI endpoint, String region, String bucket, String accessKey, String secretKey, boolean createBucket) {}
