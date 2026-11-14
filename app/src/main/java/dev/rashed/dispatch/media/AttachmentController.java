package dev.rashed.dispatch.media;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Attachments", description = "Photos and documents on a work order")
@RestController
@RequestMapping("/api/work-orders/{workOrderId}/attachments")
class AttachmentController {

    private final AttachmentService attachments;

    AttachmentController(AttachmentService attachments) {
        this.attachments = attachments;
    }

    @Operation(
            summary = "Start an upload: get a URL to send the file to",
            description = "Upload the bytes to the returned URL with the returned method and headers within 5"
                    + " minutes, then confirm. JPEG, PNG or PDF, up to 10 MB.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    UploadTicket requestUpload(@PathVariable UUID workOrderId, @Valid @RequestBody UploadRequest request) {
        return attachments.requestUpload(workOrderId, request);
    }

    @Operation(
            summary = "Finish an upload: the server checks the stored file",
            description = "409 upload-missing if nothing was uploaded yet, 409 upload-rejected (and the file is"
                    + " deleted) if its size, type or content doesn't match what you declared.")
    @PostMapping("/{attachmentId}/confirm")
    AttachmentView confirm(@PathVariable UUID workOrderId, @PathVariable UUID attachmentId) {
        return attachments.confirm(workOrderId, attachmentId);
    }

    @Operation(summary = "The work order's files, each with a download link valid for 5 minutes")
    @GetMapping
    List<AttachmentView> list(@PathVariable UUID workOrderId) {
        return attachments.list(workOrderId);
    }
}
