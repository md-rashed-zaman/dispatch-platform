package dev.rashed.dispatch.media;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** What the client is about to upload. Only a claim: confirming checks the stored file against it. */
public record UploadRequest(
        @NotBlank @Size(max = 255) String fileName,

        @NotBlank @Pattern(regexp = CONTENT_TYPES, message = "must be image/jpeg, image/png or application/pdf")
        String contentType,

        @Positive @Max(AttachmentService.MAX_SIZE_BYTES) long size) {

    /** Must list exactly the {@link FileType}s (FileTypeTest checks). */
    static final String CONTENT_TYPES = "image/jpeg|image/png|application/pdf";
}
