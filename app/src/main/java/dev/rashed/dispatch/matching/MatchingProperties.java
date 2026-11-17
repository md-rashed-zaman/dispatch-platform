package dev.rashed.dispatch.matching;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How technicians are chosen for a work order (step 3.4).
 *
 * @param strategy {@code tags}: the nearest with every required skill (Phase 1); {@code hybrid}: score nearby
 *     technicians on meaning, skills, distance, rating and reliability
 * @param poolSize how many of the nearest technicians hybrid ranking scores
 * @param minSkillCoverage the share of required skills a technician must have to be ranked at all
 */
@ConfigurationProperties("dispatch.matching")
record MatchingProperties(Strategy strategy, int poolSize, double minSkillCoverage, Weights weights) {

    enum Strategy {
        TAGS,
        HYBRID
    }

    /** Relative weights; only components that exist for a work order count (no vector: no semantic part). */
    record Weights(double semantic, double skills, double distance, double rating, double reliability) {}
}
