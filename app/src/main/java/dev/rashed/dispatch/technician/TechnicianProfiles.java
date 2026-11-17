package dev.rashed.dispatch.technician;

import dev.rashed.dispatch.shared.NotFoundException;
import java.util.UUID;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Technician profiles, cache-aside (T23): a read looks in Redis first and loads from the database on a miss;
 * an update writes the database, and the cached copy is dropped once that commits.
 */
@Service
public class TechnicianProfiles {

    public static final String CACHE = "technician-profiles";

    private final TechnicianRepository technicians;
    private final CacheManager caches;

    TechnicianProfiles(TechnicianRepository technicians, CacheManager caches) {
        this.technicians = technicians;
        this.caches = caches;
    }

    /**
     * sync: when many requests miss at once (just evicted, just expired), one loads it from the database and the
     * others wait for that result, instead of all hitting the database (per instance).
     */
    @Cacheable(cacheNames = CACHE, key = "#id", sync = true)
    @PreAuthorize("isAuthenticated()")
    @Transactional(readOnly = true)
    public TechnicianProfile find(UUID id) {
        return technicians
                .findById(id)
                .map(TechnicianProfile::of)
                .orElseThrow(() -> new NotFoundException("Technician", id));
    }

    @PreAuthorize("hasRole('TECHNICIAN')")
    @Transactional
    public TechnicianProfile update(UUID technicianId, UpdateTechnicianProfileRequest request) {
        var technician =
                technicians.findById(technicianId).orElseThrow(() -> new NotFoundException("Technician", technicianId));
        technician.updateProfile(request.skills(), request.serviceRadiusKm(), request.bio());
        return TechnicianProfile.of(technicians.save(technician)); // publishes TechnicianUpdated
    }

    /**
     * After commit, not before: dropping the copy earlier would let a read in between put the old profile back
     * for a whole TTL. A read that started before the commit can still do that (it read the old row, then
     * writes it after our evict); the TTL bounds how long that copy can live.
     */
    @TransactionalEventListener
    void on(TechnicianUpdated event) {
        var cache = caches.getCache(CACHE);
        if (cache != null) {
            cache.evict(event.technicianId());
        }
    }
}
