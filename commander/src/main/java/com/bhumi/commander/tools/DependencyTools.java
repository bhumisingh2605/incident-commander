package com.bhumi.commander.tools;

import java.util.List;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class DependencyTools {

    private final ServiceRegistry services;

    public DependencyTools(ServiceRegistry services) { this.services = services; }

    @Tool(description = "Show which services this service calls (downstream) and which services call it (upstream).")
    public String getDependencies(
            @ToolParam(description = "Service name: order-service or payment-service") String service) {

        if (!services.known(service)) {
            return "Unknown service '" + service + "'. Known services: " + services.names();
        }
        return "%s calls: %s. Called by: %s."
                .formatted(service, show(services.downstreamOf(service)), show(services.upstreamOf(service)));
    }

    private static String show(List<String> list) { return list.isEmpty() ? "none" : String.join(", ", list); }
}