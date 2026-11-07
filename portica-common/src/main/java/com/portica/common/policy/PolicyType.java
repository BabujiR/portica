package com.portica.common.policy;

import com.portica.common.policy.config.AccessControlConfig;
import com.portica.common.policy.config.AssignMessageConfig;
import com.portica.common.policy.config.CorsConfig;
import com.portica.common.policy.config.FlowCalloutConfig;
import com.portica.common.policy.config.JsonThreatProtectionConfig;
import com.portica.common.policy.config.JsonToXmlConfig;
import com.portica.common.policy.config.OAuthV2Config;
import com.portica.common.policy.config.PolicyConfig;
import com.portica.common.policy.config.QuotaConfig;
import com.portica.common.policy.config.RaiseFaultConfig;
import com.portica.common.policy.config.RegexProtectionConfig;
import com.portica.common.policy.config.ResponseCacheConfig;
import com.portica.common.policy.config.ServiceCalloutConfig;
import com.portica.common.policy.config.SpikeArrestConfig;
import com.portica.common.policy.config.VerifyApiKeyConfig;
import com.portica.common.policy.config.VerifyJwtConfig;
import com.portica.common.policy.config.XmlToJsonConfig;

import java.util.Arrays;
import java.util.Optional;

/** Every policy type Portica supports. {@link #typeName()} is what bundles use in {@code type}. */
public enum PolicyType {

    VERIFY_API_KEY("VerifyAPIKey", "Security", VerifyApiKeyConfig.class,
            "Requires a valid API key and exposes the calling app in variables."),
    OAUTH_V2("OAuthV2", "Security", OAuthV2Config.class,
            "Requires a valid OAuth 2.0 bearer access token (JWT) and optional scopes."),
    VERIFY_JWT("VerifyJWT", "Security", VerifyJwtConfig.class,
            "Verifies a signed JWT's signature and claims."),
    ACCESS_CONTROL("AccessControl", "Security", AccessControlConfig.class,
            "Allows or denies clients by IP address or CIDR range."),
    REGULAR_EXPRESSION_PROTECTION("RegularExpressionProtection", "Threat protection", RegexProtectionConfig.class,
            "Blocks requests whose path, query, headers or body match dangerous patterns."),
    JSON_THREAT_PROTECTION("JSONThreatProtection", "Threat protection", JsonThreatProtectionConfig.class,
            "Rejects JSON payloads that are too deep, too large or too wide."),
    QUOTA("Quota", "Traffic management", QuotaConfig.class,
            "Limits requests per time window across all gateway instances."),
    SPIKE_ARREST("SpikeArrest", "Traffic management", SpikeArrestConfig.class,
            "Smooths sudden traffic spikes per gateway instance."),
    RESPONSE_CACHE("ResponseCache", "Traffic management", ResponseCacheConfig.class,
            "Serves repeated requests from a shared cache."),
    CORS("CORS", "Mediation", CorsConfig.class,
            "Handles browser cross-origin requests."),
    ASSIGN_MESSAGE("AssignMessage", "Mediation", AssignMessageConfig.class,
            "Sets or removes headers, query parameters, body, status and variables."),
    JSON_TO_XML("JSONToXML", "Mediation", JsonToXmlConfig.class,
            "Converts a JSON body to XML."),
    XML_TO_JSON("XMLToJSON", "Mediation", XmlToJsonConfig.class,
            "Converts an XML body to JSON."),
    RAISE_FAULT("RaiseFault", "Mediation", RaiseFaultConfig.class,
            "Stops processing and returns a custom response."),
    SERVICE_CALLOUT("ServiceCallout", "Extension", ServiceCalloutConfig.class,
            "Calls another HTTP service and stores its response in variables."),
    FLOW_CALLOUT("FlowCallout", "Extension", FlowCalloutConfig.class,
            "Runs a shared flow deployed in the same environment.");

    private final String typeName;
    private final String category;
    private final Class<? extends PolicyConfig> configClass;
    private final String description;

    PolicyType(String typeName, String category, Class<? extends PolicyConfig> configClass, String description) {
        this.typeName = typeName;
        this.category = category;
        this.configClass = configClass;
        this.description = description;
    }

    public String typeName() {
        return typeName;
    }

    public String category() {
        return category;
    }

    public Class<? extends PolicyConfig> configClass() {
        return configClass;
    }

    public String description() {
        return description;
    }

    /** Case-insensitive lookup by type name, e.g. {@code verifyapikey}. */
    public static Optional<PolicyType> fromTypeName(String name) {
        if (name == null) {
            return Optional.empty();
        }
        return Arrays.stream(values()).filter(t -> t.typeName.equalsIgnoreCase(name.strip())).findFirst();
    }
}
