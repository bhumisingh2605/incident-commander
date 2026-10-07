package com.bhumi.order_service.controller;

import com.bhumi.order_service.chaos.ChaosState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/chaos")
public class ChaosController {
    private static final Logger log = LoggerFactory.getLogger(ChaosController.class);
    private final ChaosState state;

    public ChaosController(ChaosState state) { this.state = state; }

    @PostMapping("/error")
    public String error(@RequestParam(defaultValue = "true") boolean enabled) {
        state.errorMode = enabled;
        log.info("runtime flag errorMode set to {}", enabled);
        return "error mode = " + enabled;
    }

    @PostMapping("/slow")
    public String slow(@RequestParam(defaultValue = "2000") long ms) {
        state.delayMs = ms;
        log.info("runtime flag delayMs set to {}", ms);
        return "delay = " + ms + " ms";
    }

    @PostMapping("/memory-leak")
    public String leak(@RequestParam(defaultValue = "50") int mb) {
        synchronized (state.leak) {
            for (int i = 0; i < mb; i++) state.leak.add(new byte[1024 * 1024]);
            log.debug("allocated {} MB, total {} MB", mb, state.leak.size());
            return "leaked total MB = " + state.leak.size();
        }
    }

    @PostMapping("/cpu")
    public String cpu(@RequestParam(defaultValue = "30") int seconds) {
        if (System.currentTimeMillis() < state.cpuUntil) return "already burning";
        state.cpuUntil = System.currentTimeMillis() + seconds * 1000L;
        int threads = Runtime.getRuntime().availableProcessors();
        for (int i = 0; i < threads; i++) {
            Thread t = new Thread(() -> {
                while (System.currentTimeMillis() < state.cpuUntil) {
                    double ignored = Math.sqrt(Math.random());
                }
            });
            t.setDaemon(true);
            t.start();
        }
        log.debug("worker threads started: {}s on {} threads", seconds, threads);
        return "cpu burn " + seconds + "s";
    }

    @PostMapping("/reset")
    public String reset() {
        state.errorMode = false;
        state.delayMs = 0;
        state.cpuUntil = 0;
        synchronized (state.leak) { state.leak.clear(); }
        System.gc();
        log.info("runtime flags reset");
        return "reset";
    }
}