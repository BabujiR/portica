package com.portica.mcp.tools;

import java.util.List;
import java.util.Map;

/**
 * An MCP {@code CallToolResult} plus what Portica records about the call.
 *
 * @param result         the result object returned to the client
 * @param isError        the tool failed (the client and the model see the error text)
 * @param upstreamStatus HTTP status of the API call or remote server (0 when none)
 */
public record ToolResult(Map<String, Object> result, boolean isError, int upstreamStatus) {

    public static ToolResult text(String text, boolean isError, int upstreamStatus) {
        return new ToolResult(Map.of("content", List.of(Map.of("type", "text", "text", text)), "isError", isError),
                isError, upstreamStatus);
    }
}
