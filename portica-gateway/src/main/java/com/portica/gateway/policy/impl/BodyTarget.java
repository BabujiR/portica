package com.portica.gateway.policy.impl;

import com.portica.gateway.policy.MessageContext;
import com.portica.gateway.policy.PolicyPhase;
import org.springframework.http.HttpHeaders;

import java.util.function.Consumer;

/** Access to the request or response body for transformation policies. */
record BodyTarget(byte[] body, HttpHeaders headers, Consumer<byte[]> setter) {

    /** The configured message, or null when it isn't available at this point (e.g. no response yet). */
    static BodyTarget of(MessageContext ctx, String target) {
        if (target.equals("response")) {
            if (ctx.response() == null || ctx.response().body() == null) {
                return null;
            }
            return new BodyTarget(ctx.response().body(), ctx.response().headers(), ctx.response()::setBody);
        }
        if (ctx.request().body() == null) {
            return null;
        }
        return new BodyTarget(ctx.request().body(), ctx.request().headers(), ctx.request()::setBody);
    }

    static boolean needsRequestBody(String target) {
        return target.equals("request");
    }

    static boolean needsBufferedResponse(String target, PolicyPhase placement) {
        return target.equals("response") && placement == PolicyPhase.RESPONSE;
    }
}
