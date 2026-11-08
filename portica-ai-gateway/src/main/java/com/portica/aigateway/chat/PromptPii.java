package com.portica.aigateway.chat;

import com.portica.aigateway.pii.PiiScanner;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/** Applies a {@link PiiScanner} to the text of chat messages and completions, in place. */
final class PromptPii {

    private PromptPii() {
    }

    /** Redacts every text part of {@code messages} (string content and {@code type: text} parts). */
    static PiiScanner.Findings redactMessages(ObjectNode request, PiiScanner scanner) {
        List<PiiScanner.Findings> all = new ArrayList<>();
        for (JsonNode m : request.path("messages")) {
            if (!(m instanceof ObjectNode message)) {
                continue;
            }
            JsonNode content = message.path("content");
            if (content.isString()) {
                PiiScanner.Result r = scanner.redact(content.asString());
                all.add(r.findings());
                message.put("content", r.text());
            } else if (content.isArray()) {
                for (JsonNode part : content) {
                    if (part instanceof ObjectNode p && p.path("type").asString().equals("text")) {
                        PiiScanner.Result r = scanner.redact(p.path("text").asString());
                        all.add(r.findings());
                        p.put("text", r.text());
                    }
                }
            }
        }
        return PiiScanner.merge(all);
    }

    /** Redacts {@code choices[].message.content} of a chat completion. */
    static PiiScanner.Findings redactCompletion(ObjectNode completion, PiiScanner scanner) {
        List<PiiScanner.Findings> all = new ArrayList<>();
        for (JsonNode choice : completion.path("choices")) {
            if (choice.path("message") instanceof ObjectNode message && message.path("content").isString()) {
                PiiScanner.Result r = scanner.redact(message.path("content").asString());
                all.add(r.findings());
                message.put("content", r.text());
            }
        }
        return PiiScanner.merge(all);
    }
}
