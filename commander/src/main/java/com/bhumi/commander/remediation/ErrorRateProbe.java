package com.bhumi.commander.remediation;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class ErrorRateProbe {

    private final String prometheusUrl;
    private final RestClient http = RestClient.create();

    public ErrorRateProbe(@Value("${commander.prometheus-url:http://localhost:9090}") String prometheusUrl) {
        this.prometheusUrl = prometheusUrl;
    }

    /** Error ratio over the last minute, or empty when the service had no traffic. */
    public OptionalDouble errorRate(String service) {
        String base = "http_server_requests_seconds_count{application=\"%s\",uri!~\"/actuator.*\"".formatted(service);
        String all = base + "}";
        String errors = base + ",status=~\"5..\"}";
        String query = "(sum(rate(" + errors + "[1m])) or vector(0)) / sum(rate(" + all + "[1m]))";

        URI uri = URI.create(prometheusUrl + "/api/v1/query?query="
                + URLEncoder.encode(query, StandardCharsets.UTF_8));
        Map<?, ?> body = http.get().uri(uri).retrieve().body(Map.class);

        if (body != null && body.get("data") instanceof Map<?, ?> data
                && data.get("result") instanceof List<?> result && !result.isEmpty()
                && ((Map<?, ?>) result.get(0)).get("value") instanceof List<?> value && value.size() == 2) {
            double v = Double.parseDouble(String.valueOf(value.get(1)));
            if (!Double.isNaN(v)) return OptionalDouble.of(v);
        }
        return OptionalDouble.empty();
    }
}