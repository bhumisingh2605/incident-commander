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

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final String HTTP = "application=\"%1$s\",uri!~\"/actuator.*\"";

    private static final Map<String, String> QUERIES = Map.of(
            "error_rate",
            "(sum(rate(http_server_requests_seconds_count{" + HTTP + ",status=~\"5..\"}[1m])) or vector(0))"
                    + " / sum(rate(http_server_requests_seconds_count{" + HTTP + "}[1m]))",
            "request_rate",
            "sum(rate(http_server_requests_seconds_count{" + HTTP + "}[1m]))",
            "latency_p95",
            "histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{" + HTTP + "}[1m])))",
            "cpu", "process_cpu_usage{application=\"%1$s\"}",
            "heap_used_bytes", "sum(jvm_memory_used_bytes{application=\"%1$s\",area=\"heap\"})"
    );

    private final String prometheusUrl;
    private final RestClient prometheus;
    private final ServiceRegistry services;

    public MetricsTools(@Value("${commander.prometheus-url}") String prometheusUrl, ServiceRegistry services) {
        this.prometheusUrl = prometheusUrl;
        this.prometheus = RestClient.create();
        this.services = services;
    }

    @Tool(description = "Get a summarized time series for one metric of one service: current value, min, max with time of the peak, and trend.")
    public String getMetrics(
            @ToolParam(description = "Service name: order-service or payment-service") String service,
            @ToolParam(description = "One of: error_rate, request_rate, latency_p95, cpu, heap_used_bytes") String metric,
            @ToolParam(description = "How many minutes back to look, 1 to 120") int minutes) {

        if (!services.known(service)) {
            return "Unknown service '" + service + "'. Known services: " + services.names();
        }
        String template = QUERIES.get(metric);
        if (template == null) {
            return "Unknown metric '" + metric + "'. Allowed: " + QUERIES.keySet();
        }
        int mins = Math.max(1, Math.min(minutes, 120));
        long end = Instant.now().getEpochSecond();
        long start = end - mins * 60L;
        String query = template.formatted(service);

        URI uri = URI.create(prometheusUrl + "/api/v1/query_range?query="
                + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&start=" + start + "&end=" + end + "&step=15");
        try {
            Map<?, ?> body = prometheus.get().uri(uri).retrieve().body(Map.class);
            return summarize(service, metric, mins, body);
        } catch (Exception e) {
            return "Could not query Prometheus: " + e.getMessage();
        }
    }

    private String summarize(String service, String metric, int mins, Map<?, ?> body) {
        List<double[]> points = new ArrayList<>();
        if (body != null && body.get("data") instanceof Map<?, ?> data
                && data.get("result") instanceof List<?> result && !result.isEmpty()) {
            List<?> values = (List<?>) ((Map<?, ?>) result.get(0)).get("values");
            for (Object v : values) {
                List<?> pair = (List<?>) v;
                double ts = ((Number) pair.get(0)).doubleValue();
                double val = Double.parseDouble(String.valueOf(pair.get(1)));
                if (!Double.isNaN(val)) points.add(new double[] {ts, val});
            }
        }
        if (points.isEmpty()) {
            return "%s %s: no data in the last %d min (no traffic, or the metric does not exist yet)."
                    .formatted(service, metric, mins);
        }

        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE, maxTs = 0;
        for (double[] p : points) {
            if (p[1] < min) min = p[1];
            if (p[1] > max) { max = p[1]; maxTs = p[0]; }
        }
        double current = points.get(points.size() - 1)[1];

        int n = points.size();
        int third = Math.max(1, n / 3);
        double first = avg(points, 0, third);
        double last = avg(points, n - third, n);
        double range = Math.max(Math.abs(max), 1e-9);
        String trend = (last - first) > 0.2 * range ? "rising"
                : (last - first) < -0.2 * range ? "falling" : "stable";

        String peakTime = LocalTime.ofInstant(Instant.ofEpochSecond((long) maxTs), ZoneId.systemDefault()).format(TIME);
        return "%s %s over the last %d min: current %s, min %s, max %s (at %s), trend: %s."
                .formatted(service, metric, mins, fmt(metric, current), fmt(metric, min), fmt(metric, max), peakTime, trend);
    }

    private static double avg(List<double[]> pts, int from, int to) {
        double sum = 0;
        for (int i = from; i < to; i++) sum += pts.get(i)[1];
        return sum / (to - from);
    }

    private static String fmt(String metric, double v) {
        return switch (metric) {
            case "error_rate", "cpu" -> "%.1f%%".formatted(v * 100);
            case "latency_p95" -> "%.0f ms".formatted(v * 1000);
            case "heap_used_bytes" -> "%.0f MB".formatted(v / 1_048_576.0);
            default -> "%.2f req/s".formatted(v);
        };
    }
}