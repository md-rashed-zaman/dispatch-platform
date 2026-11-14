package dev.rashed.dispatch.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.rashed.dispatch.TestTechnicians;
import dev.rashed.dispatch.TestTokens;
import dev.rashed.dispatch.TestcontainersConfiguration;
import dev.rashed.dispatch.shared.Timestamps;
import dev.rashed.dispatch.tenancy.TenantContext;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/** Uploads end to end: the API hands out URLs, the bytes go straight to a real SeaweedFS over HTTP. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class AttachmentApiTests {

    static final byte[] JPEG_START = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0};

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate transaction;

    @Autowired
    S3Client s3;

    @Autowired
    S3Presigner presigner;

    @Autowired
    StorageProperties storage;

    @Autowired
    AbandonedUploadCleanup cleanup;

    final HttpClient http = HttpClient.newHttpClient();
    final UUID tenant = UUID.randomUUID();
    UUID technician;
    UUID workOrder;
    String dispatcher;
    String assignedTechnician;

    @BeforeEach
    void setUp() {
        jdbc.update("insert into company (id, name, slug) values (?, 'Company', ?)", tenant, "company-" + tenant);
        dispatcher =
                TestTokens.token(c -> c.claim("roles", List.of("DISPATCHER")).claim("tenant_id", tenant.toString()));
        technician = TestTechnicians.verified(jdbc, 23.79, 90.41, "networking");
        assignedTechnician = TestTechnicians.token(technician);
        workOrder = insertWorkOrder("ASSIGNED", technician);
    }

    @Test
    void theAssignedTechnicianUploadsAPhotoAndTheDispatcherDownloadsIt() throws Exception {
        var photo = jpeg(200_000);

        var ticket = requestUpload(assignedTechnician, "router.jpg", "image/jpeg", photo.length);
        assertThat(ticket.url()).contains("/tenants/" + tenant + "/work-orders/" + workOrder + "/");
        assertThat(put(ticket, photo).statusCode()).isEqualTo(200);
        call(post(confirmPath(ticket)), assignedTechnician)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(photo.length));

        var listing = call(get("/api/work-orders/{id}/attachments", workOrder), dispatcher)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].fileName").value("router.jpg"))
                .andExpect(jsonPath("$[0].contentType").value("image/jpeg"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String downloadUrl = JsonPath.read(listing, "$[0].downloadUrl");
        var download = http.send(
                HttpRequest.newBuilder(URI.create(downloadUrl)).build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(download.body()).isEqualTo(photo);
    }

    @Test
    void storageRefusesAnUploadThatDoesntMatchTheSignedTypeOrSize() throws Exception {
        var ticket = requestUpload(assignedTechnician, "router.jpg", "image/jpeg", 1_000);

        var otherType = HttpRequest.newBuilder(URI.create(ticket.url()))
                .header("Content-Type", "application/octet-stream")
                .PUT(HttpRequest.BodyPublishers.ofByteArray(jpeg(1_000)))
                .build();
        assertThat(http.send(otherType, HttpResponse.BodyHandlers.discarding()).statusCode())
                .isEqualTo(403);
        assertThat(put(ticket, jpeg(5_000)).statusCode()).isEqualTo(403); // more bytes than declared
    }

    @Test
    void anUploadUrlCantBePointedAtAnotherTenantsFiles() throws Exception {
        var ticket = requestUpload(assignedTechnician, "router.jpg", "image/jpeg", 1_000);
        var tampered = new UploadTicket(
                ticket.attachmentId(),
                "PUT",
                ticket.url().replace("/tenants/" + tenant, "/tenants/" + UUID.randomUUID()),
                ticket.headers(),
                ticket.expiresAt());

        assertThat(put(tampered, jpeg(1_000)).statusCode()).isEqualTo(403); // the signature covers the key
    }

    @Test
    void storageRefusesAnExpiredUploadUrl() throws Exception {
        // Same signing as the API, with a 1-second lifetime instead of 5 minutes.
        var presigned = presigner.presignPutObject(p -> p.signatureDuration(Duration.ofSeconds(1))
                .putObjectRequest(o -> o.bucket(storage.bucket())
                        .key("tenants/" + tenant + "/expiry-test.jpg")
                        .contentType("image/jpeg")));
        Thread.sleep(2_000);

        var request = HttpRequest.newBuilder(presigned.url().toURI())
                .header("Content-Type", "image/jpeg")
                .PUT(HttpRequest.BodyPublishers.ofByteArray(jpeg(100)))
                .build();
        assertThat(http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode())
                .isEqualTo(403);
    }

    @Test
    void confirmingRejectsAndDeletesAFileLargerThanDeclared() throws Exception {
        var ticket = requestUpload(assignedTechnician, "router.jpg", "image/jpeg", 1_000);
        // Someone got around the URL and stored 11 MB under the key (the check must not trust the URL alone).
        var key = keyOf(ticket);
        s3.putObject(
                p -> p.bucket(storage.bucket()).key(key).contentType("image/jpeg"),
                RequestBody.fromBytes(jpeg(11 * 1024 * 1024)));

        call(post(confirmPath(ticket)), assignedTechnician)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:dispatch:problem:upload-rejected"));
        assertThatThrownBy(() -> s3.headObject(h -> h.bucket(storage.bucket()).key(key)))
                .isInstanceOf(NoSuchKeyException.class);
        assertThat(rowCount(ticket.attachmentId())).isZero();
    }

    @Test
    void confirmingRejectsAFileThatIsntReallyAnImage() throws Exception {
        var program = new byte[1_000];
        program[0] = 'M';
        program[1] = 'Z'; // how Windows programs start
        var ticket = requestUpload(assignedTechnician, "router.jpg", "image/jpeg", program.length);
        assertThat(put(ticket, program).statusCode()).isEqualTo(200);

        call(post(confirmPath(ticket)), assignedTechnician)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:dispatch:problem:upload-rejected"))
                .andExpect(
                        jsonPath("$.detail").value("The file's content isn't a real jpg file. The file was deleted."));
    }

    @Test
    void confirmingBeforeUploadingSaysSo() throws Exception {
        var ticket = requestUpload(assignedTechnician, "router.jpg", "image/jpeg", 1_000);

        call(post(confirmPath(ticket)), assignedTechnician)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:dispatch:problem:upload-missing"));
    }

    @Test
    void tooLargeOrWrongTypesAreRefusedBeforeAnyUrlIsIssued() throws Exception {
        uploadRequest(assignedTechnician, "video.mp4", "image/jpeg", 50L * 1024 * 1024)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("size"));
        uploadRequest(assignedTechnician, "setup.exe", "application/x-msdownload", 1_000)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("contentType"));
    }

    @Test
    void otherTenantsAndUnassignedTechniciansCantSeeTheWorkOrder() throws Exception {
        var otherDispatcher = TestTokens.token(c -> c.claim("roles", List.of("DISPATCHER"))
                .claim("tenant_id", UUID.randomUUID().toString()));
        var otherTechnician = TestTechnicians.token(TestTechnicians.verified(jdbc, 23.79, 90.41, "networking"));

        uploadRequest(otherDispatcher, "a.jpg", "image/jpeg", 1_000).andExpect(status().isNotFound());
        uploadRequest(otherTechnician, "a.jpg", "image/jpeg", 1_000).andExpect(status().isNotFound());
        call(get("/api/work-orders/{id}/attachments", workOrder), otherTechnician)
                .andExpect(status().isNotFound());
    }

    @Test
    void aClosedWorkOrdersFilesCantChange() throws Exception {
        var cancelled = insertWorkOrder("CANCELLED", null);

        call(
                        post("/api/work-orders/{id}/attachments", cancelled)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(uploadJson("a.jpg", "image/jpeg", 1_000)),
                        dispatcher)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:dispatch:problem:work-order-closed"));
    }

    @Test
    void cleanupRemovesAbandonedUploadsAndTheirFilesOnly() throws Exception {
        var abandoned = requestUpload(assignedTechnician, "a.jpg", "image/jpeg", 1_000);
        put(abandoned, jpeg(1_000));
        var kept = requestUpload(assignedTechnician, "b.jpg", "image/jpeg", 1_000);
        put(kept, jpeg(1_000));
        call(post(confirmPath(kept)), assignedTechnician).andExpect(status().isOk());
        var recent = requestUpload(assignedTechnician, "c.jpg", "image/jpeg", 1_000);
        asTenant(() ->
                jdbc.update("update attachment set created_at = now() - interval '2 hours' where id = any(?)", (Object)
                        new UUID[] {abandoned.attachmentId(), kept.attachmentId()}));

        cleanup.removeOlderThan(Timestamps.now().minus(AbandonedUploadCleanup.ABANDONED_AFTER));

        assertThat(rowCount(abandoned.attachmentId())).isZero();
        assertThatThrownBy(() -> s3.headObject(h -> h.bucket(storage.bucket()).key(keyOf(abandoned))))
                .isInstanceOf(NoSuchKeyException.class);
        assertThat(rowCount(kept.attachmentId())).isOne(); // confirmed: never cleaned up
        assertThat(rowCount(recent.attachmentId())).isOne(); // may still be uploading
    }

    private UploadTicket requestUpload(String token, String fileName, String contentType, long size) throws Exception {
        var body = uploadRequest(token, fileName, contentType, size)
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        Map<String, String> headers = JsonPath.read(body, "$.headers");
        return new UploadTicket(
                UUID.fromString(JsonPath.read(body, "$.attachmentId")),
                JsonPath.read(body, "$.method"),
                JsonPath.read(body, "$.url"),
                headers,
                null);
    }

    private ResultActions uploadRequest(String token, String fileName, String contentType, long size) throws Exception {
        return call(
                post("/api/work-orders/{id}/attachments", workOrder)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(uploadJson(fileName, contentType, size)),
                token);
    }

    private static String uploadJson(String fileName, String contentType, long size) {
        return """
                {"fileName": "%s", "contentType": "%s", "size": %d}""".formatted(fileName, contentType, size);
    }

    /** Uploads like a phone would: the ticket's method, URL and headers, the bytes, nothing else. */
    private HttpResponse<Void> put(UploadTicket ticket, byte[] bytes) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(ticket.url()));
        ticket.headers().forEach((name, value) -> {
            if (!name.equalsIgnoreCase("content-length")) { // Java's client sets it from the body itself
                request.header(name, value);
            }
        });
        return http.send(
                request.method(ticket.method(), HttpRequest.BodyPublishers.ofByteArray(bytes))
                        .build(),
                HttpResponse.BodyHandlers.discarding());
    }

    private String confirmPath(UploadTicket ticket) {
        return "/api/work-orders/" + workOrder + "/attachments/" + ticket.attachmentId() + "/confirm";
    }

    private String keyOf(UploadTicket ticket) {
        var path = URI.create(ticket.url()).getPath();
        return path.substring(path.indexOf("/tenants/") + 1);
    }

    private long rowCount(UUID attachmentId) {
        return asTenant(
                () -> jdbc.queryForObject("select count(*) from attachment where id = ?", Long.class, attachmentId));
    }

    private UUID insertWorkOrder(String status, UUID technicianId) {
        var id = UUID.randomUUID();
        asTenant(() -> jdbc.update("""
                insert into work_order (id, tenant_id, title, description, status, site_address, site_latitude,
                    site_longitude, required_skills, window_starts_at, window_ends_at, budget_minor,
                    budget_currency, technician_id)
                values (?, ?, 'Router down', 'No internet', ?, 'Gulshan, Dhaka', 23.79, 90.41, '{networking}',
                    now(), now() + interval '4 hours', 250000, 'BDT', ?)
                """, id, tenant, status, technicianId));
        return id;
    }

    private static byte[] jpeg(int size) {
        var bytes = new byte[size];
        ThreadLocalRandom.current().nextBytes(bytes);
        System.arraycopy(JPEG_START, 0, bytes, 0, JPEG_START.length);
        return bytes;
    }

    private <T> T asTenant(Supplier<T> work) {
        return TenantContext.callAs(tenant, () -> transaction.execute(s -> work.get()));
    }

    private ResultActions call(MockHttpServletRequestBuilder request, String token) throws Exception {
        return mvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }
}
