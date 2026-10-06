package com.bhumi.commander.api;

import com.bhumi.commander.agent.AnalystAgent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/debug")
public class AnalystDebugController {

    private final AnalystAgent analyst;

    public AnalystDebugController(AnalystAgent analyst) { this.analyst = analyst; }

    @GetMapping("/analyze")
    public AnalystAgent.Result analyze(@RequestParam(defaultValue =
            "HighErrorRate firing on order-service: Error rate above 5% on order-service") String alert) {
        return analyst.analyze(alert);
    }
}