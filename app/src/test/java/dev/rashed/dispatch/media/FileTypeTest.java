package dev.rashed.dispatch.media;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class FileTypeTest {

    @Test
    void theRequestValidationAcceptsExactlyTheKnownFileTypes() {
        var validated = Arrays.asList(UploadRequest.CONTENT_TYPES.split("\\|"));

        assertThat(validated)
                .containsExactlyInAnyOrderElementsOf(
                        Arrays.stream(FileType.values()).map(t -> t.contentType).toList());
    }

    @Test
    void recognizesFilesByTheirFirstBytes() {
        assertThat(FileType.PNG.matches(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A}))
                .isTrue();
        assertThat(FileType.PDF.matches("%PDF-1.7".getBytes())).isTrue();
        assertThat(FileType.JPEG.matches("%PDF-1.7".getBytes())).isFalse();
        assertThat(FileType.JPEG.matches(new byte[] {(byte) 0xFF})).isFalse(); // too short
    }
}
