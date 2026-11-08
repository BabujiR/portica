package com.portica.management.ai;

import com.portica.common.validation.ResourceName;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Phase 7 AI gateway API: LLM providers and AI proxies. */
public final class AiDtos {

    /** Model names as providers spell them, e.g. {@code gpt-4o-mini} or {@code claude-sonnet-4-5}. */
    static final String MODEL = "^[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}$";
    /** Route names clients put in {@code model}, e.g. {@code fast} or {@code chat-default}. */
    static final String ROUTE = "^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$";

    private AiDtos() {
    }

    public enum AiProviderType { OPENAI, ANTHROPIC }

    /** How clients authenticate at the gateway: an app API key ({@code x-api-key}) or a Portica OAuth token. */
    public enum ClientAuth { API_KEY, OAUTH }

    public enum PiiAction { OFF, REDACT, BLOCK }

    public enum PiiType { EMAIL, PHONE, CREDIT_CARD, SSN, IP_ADDRESS, IBAN }

    /** A model a provider serves, with list prices used for cost tracking (US dollars per million tokens). */
    public record AiModel(
            @NotBlank @Pattern(regexp = MODEL) @Schema(example = "gpt-4o-mini") String name,
            @NotNull @DecimalMin("0") @DecimalMax("100000") @Schema(example = "0.15") BigDecimal inputCostPerMillion,
            @NotNull @DecimalMin("0") @DecimalMax("100000") @Schema(example = "0.60") BigDecimal outputCostPerMillion) {
    }

    public record CreateAiProviderRequest(
            @NotBlank @ResourceName @Schema(example = "openai") String name,
            @Size(max = 128) String displayName,
            @NotNull AiProviderType type,
            @Size(max = 2048) @Schema(description = "API base URL; defaults to the provider's public API",
                    example = "https://api.openai.com/v1") String baseUrl,
            @Size(max = 4096) @Schema(description = "Provider API key (write-only)") String apiKey,
            @NotNull @Size(max = 200) List<@Valid AiModel> models,
            Boolean enabled) {
    }

    /** Full replacement; a null {@code apiKey} keeps the stored key, an empty one removes it. */
    public record UpdateAiProviderRequest(
            @Size(max = 128) String displayName,
            @Size(max = 2048) String baseUrl,
            @Size(max = 4096) String apiKey,
            @NotNull @Size(max = 200) List<@Valid AiModel> models,
            Boolean enabled) {
    }

    public record AiProviderResponse(UUID id, String name, String displayName, AiProviderType type, String baseUrl,
                                     List<AiModel> models, boolean apiKeySet, String apiKeyHint, boolean enabled,
                                     Instant createdAt, String createdBy, Instant updatedAt, String updatedBy) {
    }

    /** One provider model a route can use. */
    public record AiTarget(@NotBlank @ResourceName String provider, @NotBlank @Pattern(regexp = MODEL) String model) {
    }

    /**
     * A name clients use as {@code model}; Portica tries its targets in order, moving to the next on
     * rate limits (429), server errors and timeouts.
     */
    public record AiRoute(@NotBlank @Pattern(regexp = ROUTE) @Schema(example = "fast") String name,
                          @NotEmpty @Size(max = 5) List<@Valid AiTarget> targets) {
    }

    /** Tokens (prompt + completion) an app may use per window. */
    public record TokenQuota(
            @NotNull @Min(1) @Max(1_000_000_000_000L) @Schema(example = "100000") Long tokens,
            @Min(1) @Max(10_000) Integer interval,
            @Pattern(regexp = "minute|hour|day|week|month") @Schema(example = "day") String timeUnit) {

        public TokenQuota {
            interval = interval == null ? 1 : interval;
            timeUnit = timeUnit == null ? "day" : timeUnit;
        }
    }

    /**
     * Personal data in prompts: OFF, REDACT (replace with placeholders such as {@code [EMAIL]} before the
     * provider sees it) or BLOCK (reject the call with 400). {@code types} empty means all types.
     */
    public record PiiPolicy(PiiAction action, List<PiiType> types, Boolean scanResponses) {

        public PiiPolicy {
            action = action == null ? PiiAction.OFF : action;
            types = types == null ? List.of() : List.copyOf(types);
            scanResponses = scanResponses != null && scanResponses;
        }
    }

    /** What the AI gateway needs to serve an AI proxy (stored as JSON). */
    public record AiProxySpec(List<String> environments, ClientAuth auth, List<AiRoute> routes, String defaultRoute,
                              boolean allowProviderModels, TokenQuota tokenQuota, PiiPolicy pii,
                              int timeoutSeconds, Integer maxOutputTokens) {
    }

    public record CreateAiProxyRequest(
            @NotBlank @ResourceName @Size(max = 60) @Schema(example = "assistant") String name,
            @Size(max = 128) String displayName,
            @Size(max = 1024) String description,
            @Size(max = 50) @Schema(description = "Environments where it's served; empty means all")
            List<@ResourceName String> environments,
            ClientAuth auth,
            @NotEmpty @Size(max = 50) List<@Valid AiRoute> routes,
            @Pattern(regexp = ROUTE) @Schema(description = "Route used when the request names no model") String defaultRoute,
            @Schema(description = "Also accept 'provider/model' for any model a used provider lists") Boolean allowProviderModels,
            @Valid TokenQuota tokenQuota,
            @Valid PiiPolicy pii,
            @Min(5) @Max(600) Integer timeoutSeconds,
            @Min(1) @Max(1_000_000) Integer maxOutputTokens) {
    }

    public record UpdateAiProxyRequest(
            @Size(max = 128) String displayName,
            @Size(max = 1024) String description,
            @Size(max = 50) List<@ResourceName String> environments,
            ClientAuth auth,
            @NotEmpty @Size(max = 50) List<@Valid AiRoute> routes,
            @Pattern(regexp = ROUTE) String defaultRoute,
            Boolean allowProviderModels,
            @Valid TokenQuota tokenQuota,
            @Valid PiiPolicy pii,
            @Min(5) @Max(600) Integer timeoutSeconds,
            @Min(1) @Max(1_000_000) Integer maxOutputTokens) {
    }

    public record AiProxyResponse(UUID id, String name, String displayName, String description,
                                  List<String> environments, ClientAuth auth, List<AiRoute> routes,
                                  String defaultRoute, boolean allowProviderModels, TokenQuota tokenQuota,
                                  PiiPolicy pii, int timeoutSeconds, Integer maxOutputTokens,
                                  @Schema(description = "API proxy name to list in API products to grant apps access")
                                  String gatewayProxy,
                                  @Schema(description = "Gateway path, below /{org}/{env}") String gatewayPath,
                                  Instant createdAt, String createdBy, Instant updatedAt, String updatedBy) {
    }
}
