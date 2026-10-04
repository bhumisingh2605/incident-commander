package com.bhumi.payment_service.controller;

import com.bhumi.payment_service.chaos.ChaosState;
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
        log.warn("CHAOS error mode = {}", enabled);
        return "error mode = " + enabled;
    }

    @PostMapping("/slow")
    public String slow(@RequestParam(defaultValue = "2000") long ms) {
        state.delayMs = ms;
        log.warn("CHAOS delay = {} ms", ms);
        return "delay = " + ms + " ms";
    }

    @PostMapping("/memory-leak")
    public String leak(@RequestParam(defaultValue = "50") int mb) {
        synchronized (state.leak) {
            for (int i = 0; i < mb; i++) state.leak.add(new byte[1024 * 1024]);
            log.warn("CHAOS leaked {} MB, total {} MB", mb, state.leak.size());
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
                while (System.currentTimeMillis() < state.cpuUntil) Math.sqrt(Math.random());
            });
            t.setDaemon(true);
            t.start();
        }
        log.warn("CHAOS cpu burn for {}s on {} threads", seconds, threads);
        return "cpu burn " + seconds + "s";
    }

    @PostMapping("/reset")
    public String reset() {
        state.errorMode = false;
        state.delayMs = 0;
        state.cpuUntil = 0;
        synchronized (state.leak) { state.leak.clear(); }
        System.gc();
        log.warn("CHAOS reset");
        return "reset";
    }
}
