package com.bhumi.commander.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AlertmanagerPayload(String status, List<Alert> alerts) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Alert(String status, String fingerprint,
                        Map<String, String> labels,
                        Map<String, String> annotations,
                        String startsAt) {}
}