package com.bhumi.commander.tools;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class DeployTools {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private final JdbcClient jdbc;

    public DeployTools(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Tool(description = "List recent deployments across all services, newest first, with how long ago each happened. Use it to check whether a recent change could explain an incident. An empty result means nothing was deployed in that period.")
    public String getRecentDeploys(
            @ToolParam(description = "How many minutes back to look, 1 to 1440") int minutes) {

        int mins = Math.max(1, Math.min(minutes, 1440));
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime since = now.minusMinutes(mins);

        List<String> rows = jdbc.sql("""
                SELECT service, version, deployed_by, notes, deployed_at
                FROM deployments WHERE deployed_at > :since
                ORDER BY deployed_at DESC LIMIT 10
                """)
                .param("since", since)
                .query((rs, n) -> {
                    OffsetDateTime at = rs.getObject("deployed_at", OffsetDateTime.class);
                    long ago = Duration.between(at, now).toMinutes();
                    String age = ago < 1 ? "less than 1 min ago" : ago + " min ago";
                    String notes = rs.getString("notes");
                    return "%s -> %s deployed %s (at %s) by %s%s".formatted(
                            rs.getString("service"), rs.getString("version"), age,
                            at.atZoneSameInstant(ZoneId.systemDefault()).format(TIME),
                            rs.getString("deployed_by"),
                            (notes == null || notes.isBlank()) ? "" : " (" + notes + ")");
                })
                .list();

        if (rows.isEmpty()) {
            return "No deployments recorded in the last " + mins + " min.";
        }
        return "Deployments in the last " + mins + " min (newest first):\n" + String.join("\n", rows);
    }
}