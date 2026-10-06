package com.bhumi.commander.agent;

import java.util.List;
import java.util.Set;

public record RootCause(
        String summary,
        String suspectedService,
        String category,
        List<String> evidence,
        double confidence,
        List<String> suggestedActions) {

    private static final Set<String> CATEGORIES = Set.of(
            "BAD_DEPLOY", "APPLICATION_ERROR", "DEPENDENCY_FAILURE", "RESOURCE_EXHAUSTION",
            "SLOW_DEPENDENCY", "SERVICE_DOWN", "UNKNOWN");

    // Never trust model output: normalize it so downstream code can rely on the shape.
    public RootCause {
        category = category == null ? "UNKNOWN" : category.trim().toUpperCase();
        if (!CATEGORIES.contains(category)) category = "UNKNOWN";
        confidence = Double.isNaN(confidence) ? 0.0 : Math.max(0.0, Math.min(1.0, confidence));
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        suggestedActions = suggestedActions == null ? List.of() : List.copyOf(suggestedActions);
        summary = summary == null ? "" : summary;
        suspectedService = suspectedService == null ? "unknown" : suspectedService;
    }
}