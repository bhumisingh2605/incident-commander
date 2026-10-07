package com.bhumi.order_service.chaos;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class ChaosState {
    public volatile boolean errorMode = false;
    public volatile long delayMs = 0;
    public volatile long cpuUntil = 0;
    public final List<byte[]> leak = new ArrayList<>();
}