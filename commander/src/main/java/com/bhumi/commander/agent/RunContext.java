package com.bhumi.commander.agent;

/** Tracks the current agent run on this thread: its id and how many tool calls it has made. */
public final class RunContext {

    public static final int MAX_TOOL_CALLS = 8;

    private static final ThreadLocal<RunContext> CURRENT = new ThreadLocal<>();

    private final String runId;
    private int toolCalls;

    private RunContext(String runId) { this.runId = runId; }

    public static RunContext start(String runId) {
        RunContext ctx = new RunContext(runId);
        CURRENT.set(ctx);
        return ctx;
    }

    public static RunContext current() { return CURRENT.get(); }

    public static void clear() { CURRENT.remove(); }

    public String runId() { return runId; }

    public int toolCalls() { return toolCalls; }

    public int increment() { return ++toolCalls; }
}