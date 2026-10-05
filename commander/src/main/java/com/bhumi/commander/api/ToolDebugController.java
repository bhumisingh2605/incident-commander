package com.bhumi.commander.api;

import com.bhumi.commander.tools.DependencyTools;
import com.bhumi.commander.tools.HealthTools;
import com.bhumi.commander.tools.MetricsTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/debug")
public class ToolDebugController {

    private final MetricsTools metrics;
    private final HealthTools health;
    private final DependencyTools deps;
    private final ChatClient chat;

    public ToolDebugController(ChatClient.Builder builder, MetricsTools metrics,
                               HealthTools health, DependencyTools deps) {
        // Thinking off: Qwen3 otherwise spends hundreds of hidden tokens per step,
        // which is very slow on CPU.
        this.chat = builder
                .defaultOptions(OllamaChatOptions.builder()
                        .model("qwen3:8b")
                        .temperature(0.1)
                        .disableThinking())
                .build();
        this.metrics = metrics;
        this.health = health;
        this.deps = deps;
    }

    @GetMapping("/metrics")
    public String metricsEndpoint(@RequestParam String service, @RequestParam String metric,
                                  @RequestParam(defaultValue = "10") int minutes) {
        return metrics.getMetrics(service, metric, minutes);
    }

    @GetMapping("/health")
    public String healthEndpoint(@RequestParam String service) {
        return health.getServiceHealth(service);
    }

    @GetMapping("/deps")
    public String depsEndpoint(@RequestParam String service) {
        return deps.getDependencies(service);
    }

    @GetMapping("/ping")
    public String ping() {
        return chat.prompt()
                .user("Reply with the single word OK")
                .call()
                .content();
    }

    @GetMapping("/ask")
    public String ask(@RequestParam String q) {
        return chat.prompt()
                .system("You are an SRE assistant. Use the tools to answer with real data. Never guess.")
                .user(q)
                .tools(metrics, health, deps)
                .call()
                .content();
    }
}