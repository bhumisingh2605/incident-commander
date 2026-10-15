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
public class LatencyProbe {

    private final String prometheusUrl;
    private final RestClient http = RestClient.create();

    public LatencyProbe(@Value("${commander.prometheus-url:http://localhost:9090}") String prometheusUrl) {
        this.prometheusUrl = prometheusUrl;
    }

    /** p95 latency in seconds over the last minute, or empty when there is no usable data. */
    public OptionalDouble p95Seconds(String service) {
        String query = ("histogram_quantile(0.95, sum by (le) (rate("
                + "http_server_requests_seconds_bucket{application=\"%s\",uri!~\"/actuator.*\"}[5m])))").formatted(service);
        URI uri = URI.create(prometheusUrl + "/api/v1/query?query="
                + URLEncoder.encode(query, StandardCharsets.UTF_8));
        Map<?, ?> body = http.get().uri(uri).retrieve().body(Map.class);

        if (body != null && body.get("data") instanceof Map<?, ?> data
                && data.get("result") instanceof List<?> result && !result.isEmpty()
                && ((Map<?, ?>) result.get(0)).get("value") instanceof List<?> value && value.size() == 2) {
            try {
                double v = Double.parseDouble(String.valueOf(value.get(1)));
                if (!Double.isNaN(v) && !Double.isInfinite(v)) return OptionalDouble.of(v);
            } catch (NumberFormatException e) {
                // "+Inf" or "NaN": treat as no usable data, so the model investigates
            }
        }
        return OptionalDouble.empty();
    }
}