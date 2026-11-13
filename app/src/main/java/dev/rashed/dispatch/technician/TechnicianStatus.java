package dev.rashed.dispatch.technician;

public enum TechnicianStatus {
    /** Signed up; a platform admin hasn't checked their documents yet. Receives no offers. */
    PENDING_VERIFICATION,
    VERIFIED,
    SUSPENDED
}
