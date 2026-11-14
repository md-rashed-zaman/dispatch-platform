package dev.rashed.dispatch.media;

import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Configuration
@EnableConfigurationProperties(StorageProperties.class)
@EnableScheduling // the cleanup of abandoned uploads
class StorageConfig {

    // Path-style URLs (http://host/bucket/key): S3 stand-ins don't do bucket subdomains, and AWS accepts both.
    private static final S3Configuration PATH_STYLE =
            S3Configuration.builder().pathStyleAccessEnabled(true).build();

    @Bean
    S3Client s3Client(StorageProperties storage) {
        return S3Client.builder()
                .endpointOverride(storage.endpoint())
                .region(Region.of(storage.region()))
                .credentialsProvider(credentials(storage))
                .serviceConfiguration(PATH_STYLE)
                .build();
    }

    @Bean
    S3Presigner s3Presigner(StorageProperties storage) {
        return S3Presigner.builder()
                .endpointOverride(storage.endpoint())
                .region(Region.of(storage.region()))
                .credentialsProvider(credentials(storage))
                .serviceConfiguration(PATH_STYLE)
                .build();
    }

    @Bean
    ApplicationRunner createBucketIfMissing(S3Client s3, StorageProperties storage) {
        return args -> {
            if (!storage.createBucket()) {
                return;
            }
            try {
                s3.headBucket(b -> b.bucket(storage.bucket()));
            } catch (NoSuchBucketException e) {
                s3.createBucket(b -> b.bucket(storage.bucket()));
            }
        };
    }

    private static StaticCredentialsProvider credentials(StorageProperties storage) {
        return StaticCredentialsProvider.create(AwsBasicCredentials.create(storage.accessKey(), storage.secretKey()));
    }
}
