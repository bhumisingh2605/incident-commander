package com.bhumi.commander.api;

import com.bhumi.commander.agent.AnalystAgent;
import com.bhumi.commander.agent.FastAnalyst;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/debug")
public class FastAnalystDebugController {

    private final FastAnalyst analyst;

    public FastAnalystDebugController(FastAnalyst analyst) { this.analyst = analyst; }

    @GetMapping("/analyze-fast")
    public AnalystAgent.Result analyze(@RequestParam(defaultValue =
            "HighErrorRate firing on order-service: Error rate above 5% on order-service") String alert) {
        return analyst.analyze(alert);
    }
}