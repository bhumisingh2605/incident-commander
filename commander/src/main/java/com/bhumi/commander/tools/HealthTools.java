package com.bhumi.commander.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
public class HealthTools {

    private final RestClient http;
    private final ServiceRegistry services;

    public HealthTools(ServiceRegistry services) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000);
        factory.setReadTimeout(3000);
        this.http = RestClient.builder().requestFactory(factory).build();
        this.services = services;
    }

    @Tool(description = "Check whether a service is up. Returns UP, DOWN, or UNREACHABLE.")
    public String getServiceHealth(
            @ToolParam(description = "Service name: order-service or payment-service") String service) {

        if (!services.known(service)) {
            return "Unknown service '" + service + "'. Known services: " + services.names();
        }
        try {
            http.get().uri(services.url(service) + "/actuator/health").retrieve().toBodilessEntity();
            return service + " health: UP";
        } catch (RestClientResponseException e) {
            return service + " health: DOWN (HTTP " + e.getStatusCode().value() + ")";
        } catch (Exception e) {
            return service + " health: UNREACHABLE (no response)";
        }
    }
}