package dev.rashed.dispatch.review;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The completion audit (step 3.5).
 *
 * @param autoApprove the switch for approving without a person; off sends every job to the review queue with the
 *     AI's assessment attached
 * @param threshold the confidence an all-MET assessment needs to approve on its own (from the eval)
 * @param maxPhotos photos sent to the model per job (about 1,000 tokens each)
 */
@ConfigurationProperties("dispatch.review")
record ReviewProperties(boolean autoApprove, double threshold, int maxPhotos) {}
