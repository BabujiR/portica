package com.portica.aigateway;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A mock LLM provider for tests: speaks OpenAI's chat completions API under {@code /openai} and
 * Anthropic's Messages API under {@code /anthropic}, both with and without streaming. {@code /busy}
 * always answers 429 (to test fallback). Every request is recorded.
 */
public class MockLlmServer implements AutoCloseable {

    public record Recorded(String path, Map<String, List<String>> headers, JsonNode body) {
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpServer server;
    public final List<Recorded> requests = new CopyOnWriteArrayList<>();

    public MockLlmServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/openai/chat/completions", this::openAi);
        server.createContext("/anthropic/messages", this::anthropic);
        server.createContext("/busy/chat/completions", ex -> {
            record(ex);
            send(ex, 429, "application/json", "{\"error\":{\"message\":\"Rate limit reached\",\"type\":\"requests\"}}");
        });
        server.start();
    }

    public String url(String prefix) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/" + prefix;
    }

    public List<Recorded> requestsTo(String prefix) {
        return requests.stream().filter(r -> r.path().startsWith("/" + prefix + "/")).toList();
    }

    private JsonNode record(HttpExchange ex) throws IOException {
        String text = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        JsonNode body = text.isBlank() ? JSON.createObjectNode() : JSON.readTree(text);
        requests.add(new Recorded(ex.getRequestURI().getPath(), Map.copyOf(ex.getRequestHeaders()), body));
        return body;
    }

    private void openAi(HttpExchange ex) throws IOException {
        JsonNode body = record(ex);
        if (!"Bearer sk-test-openai".equals(ex.getRequestHeaders().getFirst("Authorization"))) {
            send(ex, 401, "application/json", "{\"error\":{\"message\":\"Incorrect API key\"}}");
            return;
        }
        String model = body.path("model").asString();
        if (body.path("stream").asBoolean(false)) {
            StringBuilder sse = new StringBuilder();
            for (String chunk : List.of(
                    "{\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"model\":\"%s\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"\"},\"finish_reason\":null}]}",
                    "{\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"model\":\"%s\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Hello\"},\"finish_reason\":null}]}",
                    "{\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"model\":\"%s\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}",
                    "{\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"model\":\"%s\",\"choices\":[],\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":8,\"total_tokens\":20}}")) {
                sse.append("data: ").append(chunk.formatted(model)).append("\n\n");
            }
            sse.append("data: [DONE]\n\n");
            send(ex, 200, "text/event-stream", sse.toString());
            return;
        }
        send(ex, 200, "application/json", """
                {"id":"chatcmpl-1","object":"chat.completion","created":1,"model":"%s",
                 "choices":[{"index":0,"message":{"role":"assistant","content":"Hello from OpenAI. Mail me at jane@example.com"},"finish_reason":"stop"}],
                 "usage":{"prompt_tokens":12,"completion_tokens":8,"total_tokens":20}}""".formatted(model));
    }

    private void anthropic(HttpExchange ex) throws IOException {
        JsonNode body = record(ex);
        if (!"sk-test-anthropic".equals(ex.getRequestHeaders().getFirst("x-api-key"))
                || ex.getRequestHeaders().getFirst("anthropic-version") == null) {
            send(ex, 401, "application/json", "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"invalid x-api-key\"}}");
            return;
        }
        String model = body.path("model").asString();
        if (body.path("stream").asBoolean(false)) {
            String[][] events = {
                    {"message_start", "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\",\"model\":\"" + model + "\",\"usage\":{\"input_tokens\":20,\"output_tokens\":1}}}"},
                    {"content_block_start", "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}"},
                    {"content_block_delta", "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Hi\"}}"},
                    {"content_block_delta", "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\" there\"}}"},
                    {"content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}"},
                    {"message_delta", "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":5}}"},
                    {"message_stop", "{\"type\":\"message_stop\"}"}};
            StringBuilder sse = new StringBuilder();
            for (String[] e : events) {
                sse.append("event: ").append(e[0]).append("\ndata: ").append(e[1]).append("\n\n");
            }
            send(ex, 200, "text/event-stream", sse.toString());
            return;
        }
        send(ex, 200, "application/json", """
                {"id":"msg_1","type":"message","role":"assistant","model":"%s",
                 "content":[{"type":"text","text":"Hi from Claude"}],"stop_reason":"end_turn",
                 "usage":{"input_tokens":20,"output_tokens":5}}""".formatted(model));
    }

    private static void send(HttpExchange ex, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
