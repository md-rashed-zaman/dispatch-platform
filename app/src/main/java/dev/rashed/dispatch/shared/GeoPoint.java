package dev.rashed.dispatch.shared;

import jakarta.persistence.Embeddable;

/**
 * A WGS 84 coordinate (SRID 4326, what GPS and maps use). Stored as two columns; Postgres derives a
 * PostGIS geography column from them for distance queries, so the Java model needs no spatial library.
 */
@Embeddable
public record GeoPoint(double latitude, double longitude) {

    public GeoPoint {
        if (latitude < -90 || latitude > 90) {
            throw new IllegalArgumentException("Latitude must be between -90 and 90: " + latitude);
        }
        if (longitude < -180 || longitude > 180) {
            throw new IllegalArgumentException("Longitude must be between -180 and 180: " + longitude);
        }
    }
}
