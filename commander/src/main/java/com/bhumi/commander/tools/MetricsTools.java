
package com.bhumi.commander.tools;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class MetricsTools {

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm:ss");

    private static final String HTTP =
            "application=\"%1$s\",uri!~\"/actuator.*\"";

    private static final Map<String, String> QUERIES = Map.of(
            "error_rate",
            "(sum(rate(http_server_requests_seconds_count{" + HTTP
                    + ",status=~\"5..\"}[1m])) or vector(0))"
                    + " / sum(rate(http_server_requests_seconds_count{"
                    + HTTP + "}[1m]))",

            "request_rate",
            "sum(rate(http_server_requests_seconds_count{"
                    + HTTP + "}[1m]))",

            "latency_p95",
            "histogram_quantile(0.95, sum by (le) "
                    + "(rate(http_server_requests_seconds_bucket{"
                    + HTTP + "}[1m])))",

            "cpu",
            "process_cpu_usage{application=\"%1$s\"}",

            "heap_used_bytes",
            "sum(jvm_memory_used_bytes{application=\"%1$s\",area=\"heap\"})"
    );

    private final String prometheusUrl;
    private final RestClient prometheus;
    private final ServiceRegistry services;

    public MetricsTools(
            @Value("${commander.prometheus-url}") String prometheusUrl,
            ServiceRegistry services) {

        this.prometheusUrl = prometheusUrl;
        this.prometheus = RestClient.create();
        this.services = services;
    }

    @Tool(description = "Get a summarized time series for one metric of one service: current value, min, max with time of the peak, and trend.")
    public String getMetrics(
            @ToolParam(description = "Service name: order-service or payment-service")
            String service,

            @ToolParam(description = "One of: error_rate, request_rate, latency_p95, cpu, heap_used_bytes")
            String metric,

            @ToolParam(description = "How many minutes back to look, 1 to 120")
            int minutes) {

        if (!services.known(service)) {
            return "Unknown service '" + service
                    + "'. Known services: " + services.names();
        }

        String template = QUERIES.get(metric);

        if (template == null) {
            return "Unknown metric '" + metric
                    + "'. Allowed: " + QUERIES.keySet();
        }

        int mins = Math.max(1, Math.min(minutes, 120));

        try {
            String query;

            if ("error_rate".equals(metric)) {

                String matcher = HTTP.formatted(service);

                // Check whether any non-actuator requests occurred
                // during the requested time window.
                String trafficQuery =
                        "(sum(increase(http_server_requests_seconds_count{"
                                + matcher + "}[" + mins + "m])) or vector(0))";

                Double traffic = queryInstant(trafficQuery);

                if (traffic == null) {
                    return "Could not determine whether "
                            + service + " had traffic in the last "
                            + mins + " min.";
                }

                if (traffic <= 0.0) {
                    return ("%s error_rate: no traffic in the last %d min; "
                            + "error rate cannot be calculated.")
                            .formatted(service, mins);
                }

                // Traffic exists, so calculate the 5xx percentage.
                // The numerator defaults to zero when no 5xx series exists.
                query =
                        "(sum(rate(http_server_requests_seconds_count{"
                                + matcher
                                + ",status=~\"5..\"}["
                                + mins + "m])) or vector(0))"
                                + " / sum(rate(http_server_requests_seconds_count{"
                                + matcher + "}[" + mins + "m]))";

            } else {
                query = template.formatted(service);
            }

            long end = Instant.now().getEpochSecond();
            long start = end - mins * 60L;

            URI uri = URI.create(
                    prometheusUrl
                            + "/api/v1/query_range?query="
                            + URLEncoder.encode(query, StandardCharsets.UTF_8)
                            + "&start=" + start
                            + "&end=" + end
                            + "&step=15"
            );

            Map<?, ?> body = prometheus.get()
                    .uri(uri)
                    .retrieve()
                    .body(Map.class);

            return summarize(service, metric, mins, body);

        } catch (Exception e) {
            return "Could not query Prometheus: " + e.getMessage();
        }
    }

    /**
     * Executes an instant Prometheus query.
     * Returns 0 when Prometheus returns no matching series.
     * Returns null when the result cannot be parsed.
     */
    private Double queryInstant(String query) {

        URI uri = URI.create(
                prometheusUrl
                        + "/api/v1/query?query="
                        + URLEncoder.encode(query, StandardCharsets.UTF_8)
        );

        Map<?, ?> body = prometheus.get()
                .uri(uri)
                .retrieve()
                .body(Map.class);

        if (body == null
                || !(body.get("data") instanceof Map<?, ?> data)
                || !(data.get("result") instanceof List<?> result)
                || result.isEmpty()) {
            return 0.0;
        }

        Object rawValue = result.get(0);

        if (!(rawValue instanceof Map<?, ?> resultMap)
                || !(resultMap.get("value") instanceof List<?> pair)
                || pair.size() < 2) {
            return null;
        }

        try {
            double value = Double.parseDouble(
                    String.valueOf(pair.get(1))
            );

            return Double.isFinite(value) ? value : null;

        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String summarize(
            String service,
            String metric,
            int mins,
            Map<?, ?> body) {

        List<double[]> points = new ArrayList<>();

        if (body != null
                && body.get("data") instanceof Map<?, ?> data
                && data.get("result") instanceof List<?> result
                && !result.isEmpty()) {

            Object firstResult = result.get(0);

            if (firstResult instanceof Map<?, ?> resultMap
                    && resultMap.get("values") instanceof List<?> values) {

                for (Object value : values) {

                    if (!(value instanceof List<?> pair)
                            || pair.size() < 2) {
                        continue;
                    }

                    try {
                        double timestamp =
                                Double.parseDouble(String.valueOf(pair.get(0)));

                        double metricValue =
                                Double.parseDouble(String.valueOf(pair.get(1)));

                        if (Double.isFinite(timestamp)
                                && Double.isFinite(metricValue)) {
                            points.add(new double[]{
                                    timestamp, metricValue
                            });
                        }

                    } catch (NumberFormatException ignored) {
                        // Ignore invalid Prometheus samples.
                    }
                }
            }
        }

        if (points.isEmpty()) {
            return "%s %s: no valid data in the last %d min."
                    .formatted(service, metric, mins);
        }

        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        double maxTimestamp = 0;

        for (double[] point : points) {

            if (point[1] < min) {
                min = point[1];
            }

            if (point[1] > max) {
                max = point[1];
                maxTimestamp = point[0];
            }
        }

        double current = points.get(points.size() - 1)[1];

        int count = points.size();
        int third = Math.max(1, count / 3);

        double firstAverage = avg(points, 0, third);
        double lastAverage = avg(points, count - third, count);

        double range = Math.max(Math.abs(max), 1e-9);

        String trend =
                (lastAverage - firstAverage) > 0.2 * range
                        ? "rising"
                        : (lastAverage - firstAverage) < -0.2 * range
                                ? "falling"
                                : "stable";

        String peakTime = LocalTime.ofInstant(
                Instant.ofEpochSecond((long) maxTimestamp),
                ZoneId.systemDefault()
        ).format(TIME);

        // Format the entire concatenated string.
        return ("%s %s over the last %d min: current %s, min %s, "
                + "max %s (at %s), trend: %s.")
                .formatted(
                        service,
                        metric,
                        mins,
                        fmt(metric, current),
                        fmt(metric, min),
                        fmt(metric, max),
                        peakTime,
                        trend
                );
    }

    private static double avg(
            List<double[]> points,
            int from,
            int to) {

        double sum = 0;

        for (int i = from; i < to; i++) {
            sum += points.get(i)[1];
        }

        return sum / (to - from);
    }

    private static String fmt(String metric, double value) {

        return switch (metric) {
            case "error_rate", "cpu" ->
                    "%.1f%%".formatted(value * 100);

            case "latency_p95" ->
                    "%.0f ms".formatted(value * 1000);

            case "heap_used_bytes" ->
                    "%.0f MB".formatted(value / 1_048_576.0);

            default ->
                    "%.2f req/s".formatted(value);
        };
    }
}
