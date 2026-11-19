package dev.rashed.dispatch.flags;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Feature flags", description = "Per-company rollouts (platform admins)")
@RestController
@RequestMapping("/api/flags")
class FlagController {

    private static final Pattern STRONG_ETAG = Pattern.compile("^\"(\\d+)\"$");

    /** The whole rule set, replaced at once: what you send is what applies. {@code reason} goes to the audit. */
    record ChangeRequest(
            @NotNull Boolean enabled,
            @NotNull @Min(0) @Max(100) Integer rolloutPercent,
            @NotNull @Size(max = 1000) Set<UUID> allowTenants,
            @NotNull @Size(max = 1000) Set<UUID> denyTenants,
            @NotBlank @Size(max = 500) String reason) {}

    private final FlagService flags;

    FlagController(FlagService flags) {
        this.flags = flags;
    }

    @Operation(summary = "Every flag and its rules")
    @GetMapping
    List<Flag> all() {
        return flags.all();
    }

    @Operation(summary = "One flag; its version is the ETag to send back as If-Match")
    @GetMapping("/{key}")
    ResponseEntity<Flag> get(@PathVariable String key) {
        var flag = flags.get(key);
        return ResponseEntity.ok().eTag(String.valueOf(flag.version())).body(flag);
    }

    @Operation(
            summary = "Change a flag's rules, with the reason",
            description = "Requires If-Match with the ETag from your last read: 412 if someone changed it since.")
    @PutMapping("/{key}")
    ResponseEntity<Flag> change(
            @PathVariable String key,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody ChangeRequest request) {
        var flag = flags.change(
                key,
                expectedVersion(ifMatch),
                new FlagService.Change(
                        request.enabled(),
                        request.rolloutPercent(),
                        request.allowTenants(),
                        request.denyTenants(),
                        request.reason().strip()));
        return ResponseEntity.ok().eTag(String.valueOf(flag.version())).body(flag);
    }

    @Operation(summary = "Who changed a flag, when, why, and from what to what (newest first)")
    @GetMapping("/{key}/changes")
    List<FlagService.ChangeRecord> changes(@PathVariable String key) {
        return flags.changes(key);
    }

    @Operation(summary = "What a flag is for one company, and why (its bucket, the lists, the switch)")
    @GetMapping("/{key}/explain")
    FlagService.Explanation explain(@PathVariable String key, @RequestParam UUID tenantId) {
        return flags.explain(key, tenantId);
    }

    private static long expectedVersion(String ifMatch) {
        if (ifMatch == null) {
            throw new ErrorResponseException(
                    HttpStatus.PRECONDITION_REQUIRED,
                    ProblemDetail.forStatusAndDetail(
                            HttpStatus.PRECONDITION_REQUIRED,
                            "Send If-Match with the ETag from your last read, so you can't overwrite a change you haven't seen."),
                    null);
        }
        var matcher = STRONG_ETAG.matcher(ifMatch.strip());
        if (!matcher.matches()) {
            throw new ErrorResponseException(
                    HttpStatus.PRECONDITION_FAILED,
                    ProblemDetail.forStatusAndDetail(
                            HttpStatus.PRECONDITION_FAILED, "If-Match must be an ETag from this API, like \"3\"."),
                    null);
        }
        return Long.parseLong(matcher.group(1));
    }
}
