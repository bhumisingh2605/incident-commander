package com.bhumi.commander.tools;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class ServiceRegistry {

    private final Map<String, String> urls = Map.of(
            "order-service", "http://localhost:8081",
            "payment-service", "http://localhost:8082");

    private final Map<String, List<String>> downstream = Map.of(
            "order-service", List.of("payment-service"),
            "payment-service", List.of());

    public boolean known(String service) { return service != null && urls.containsKey(service); }
    public Set<String> names() { return urls.keySet(); }
    public String url(String service) { return urls.get(service); }
    public List<String> downstreamOf(String service) { return downstream.getOrDefault(service, List.of()); }

    public List<String> upstreamOf(String service) {
        return downstream.entrySet().stream()
                .filter(e -> e.getValue().contains(service))
                .map(Map.Entry::getKey)
                .toList();
    }
}