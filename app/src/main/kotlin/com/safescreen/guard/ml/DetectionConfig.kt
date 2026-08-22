package com.safescreen.guard.ml

object DetectionConfig {

    // ======================
    // Confidence thresholds
    // ======================
    // Much stronger baseline confidence for child-safety
    const val CONFIDENCE_THRESHOLD = 0.72f      // was 0.6f
    const val HIGH_IMPACT_CONFIDENCE = 0.90f    // was 0.85f

    // ======================
    // Box geometry filtering
    // ======================
    // Prevent tiny UI buttons / thumbnails
    const val MIN_BOX_AREA_RATIO = 0.025f       // was 0.01f
    // Prevent near-fullscreen UI shapes from triggering (ads, images, reels)
    const val MAX_BOX_AREA_RATIO = 0.85f        // was 0.95f

    // Aspect ratio range tightened to avoid long UI bars
    const val MIN_ASPECT_RATIO = 0.30f          // was 0.2
    const val MAX_ASPECT_RATIO = 3.0f           // was 5.0

    // ======================
    // Screen region exclusion
    // ======================
    // Stronger exclusion of status bar / navigation bar
    const val TOP_EXCLUSION_THRESHOLD = 0.10f   // was 0.05f
    const val BOTTOM_EXCLUSION_THRESHOLD = 0.90f // was 0.95f

    // ======================
    // Tracking / stability
    // ======================
    // Higher matching IOU reduces jitter and mismatched merges
    const val IOU_MATCH_THRESHOLD = 0.50f        // was 0.4

    // Require more consecutive frames for stable detection unless high-confidence
    const val MIN_STABLE_FRAMES = 2              // kept same, needed for stability

    // False positives clear faster; true positives persist via hysteresis
    const val BOX_TTL_MS = 650L                  // was 1200L

    // ======================
    // Fullscreen detection
    // ======================
    // Only true fullscreen unsafe content should expand
    const val FULLSCREEN_COVERAGE_THRESHOLD = 0.97f   // was 0.95f
    const val FULLSCREEN_CONFIRM_MS = 450L            // was 400L

    // ======================
    // Mask/unmask behavior
    // ======================
    // Slightly longer hold to avoid flicker during transitions (child safety)
    const val MASK_HOLD_MS = 900L                     // was 1200L but Service uses 800ms

    // ======================
    // Large image handling
    // ======================
    // DISABLED because this caused large false positives (ads, story panes)
    const val ENABLE_LARGE_IMAGE_EXPANSION = false     // was true

    // If manually enabled, require extremely strict criteria
    const val LARGE_IMAGE_RATIO = 0.45f                // was 0.35f
    const val LARGE_IMAGE_MIN_CONFIDENCE = 0.90f       // was 0.75f
}
