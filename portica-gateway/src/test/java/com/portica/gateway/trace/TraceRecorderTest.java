package com.portica.gateway.trace;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TraceRecorderTest {

    @Test
    void masksCredentialQueryParameters() {
        assertThat(TraceRecorder.maskUri("/pets?apikey=abc&limit=5&access_token=xyz"))
                .isEqualTo("/pets?apikey=*****&limit=5&access_token=*****");
        assertThat(TraceRecorder.maskUri("/pets?keyword=dog")).isEqualTo("/pets?keyword=dog");
        assertThat(TraceRecorder.maskUri(null)).isNull();
    }

    @Test
    void masksSensitiveHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("Authorization", "Bearer abc");
        headers.add("X-API-Key", "k");
        headers.add("Accept", "application/json");
        Map<String, Object> out = TraceRecorder.headers(headers);
        assertThat(out).containsEntry("Authorization", TraceRecorder.MASK).containsEntry("X-API-Key", TraceRecorder.MASK)
                .containsEntry("Accept", "application/json");
    }

    @Test
    void masksSecretVariables() {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("private.db.password", "p");
        vars.put("verifyapikey.v.client_id", "id");
        vars.put("oauth.access_token", "t");
        vars.put("apiproduct.name", "gold");
        vars.put("count", 3);
        assertThat(TraceRecorder.variables(vars)).containsEntry("private.db.password", TraceRecorder.MASK)
                .containsEntry("verifyapikey.v.client_id", TraceRecorder.MASK)
                .containsEntry("oauth.access_token", TraceRecorder.MASK)
                .containsEntry("apiproduct.name", "gold").containsEntry("count", 3);
    }
}
