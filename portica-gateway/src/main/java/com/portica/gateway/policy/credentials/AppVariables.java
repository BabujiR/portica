package com.portica.gateway.policy.credentials;

import com.portica.gateway.policy.MessageContext;
import com.portica.gateway.runtime.RuntimeModel.RuntimeQuota;

/**
 * Flow variables describing the calling app, set by VerifyAPIKey and OAuthV2. Each policy sets its own
 * prefixed copies ({@code verifyapikey.<name>.*}, {@code oauthv2.<name>.*}) and the shared ones that
 * other policies read: {@code client_id}, {@code developer.app.name}, {@code developer.app.id}, {@code developer.email},
 * {@code apiproduct.name} and {@code apiproduct.quota.limit/interval/timeunit} (Quota with useProductQuota).
 */
public final class AppVariables {

    private AppVariables() {
    }

    public static void publish(MessageContext ctx, String prefix, AppCredential app, ApiProductGrant product) {
        ctx.setVariable(prefix + "client_id", app.clientId());
        ctx.setVariable(prefix + "app.name", app.appName());
        ctx.setVariable(prefix + "app.id", app.appId());
        ctx.setVariable(prefix + "developer.email", app.developerEmail());
        ctx.setVariable(prefix + "developer.id", app.developerId());
        ctx.setVariable(prefix + "apiproduct.name", product == null ? null : product.name());
        ctx.setVariable(prefix + "apiproducts", String.join(",", app.productNames()));
        app.attributes().forEach((k, v) -> ctx.setVariable(prefix + "app.attribute." + k, v));

        ctx.setVariable("client_id", app.clientId());
        ctx.setVariable("developer.app.name", app.appName());
        ctx.setVariable("developer.app.id", app.appId());
        ctx.setVariable("developer.email", app.developerEmail());
        ctx.setVariable("apiproduct.name", product == null ? null : product.name());
        RuntimeQuota quota = product == null ? null : product.quota();
        ctx.setVariable("apiproduct.quota.limit", quota == null ? null : quota.count());
        ctx.setVariable("apiproduct.quota.interval", quota == null ? null : quota.interval());
        ctx.setVariable("apiproduct.quota.timeunit", quota == null ? null : quota.timeUnit());
        if (product != null) {
            product.attributes().forEach((k, v) -> ctx.setVariable("apiproduct.attribute." + k, v));
        }
    }
}
