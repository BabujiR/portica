package com.portica.management.secrets;

import com.portica.management.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Re-wrapping stored secrets: values written before Phase 8 move to envelope encryption and stay readable. */
class SecretRotationIntegrationTest extends AbstractIntegrationTest {

    /** Same key as AbstractIntegrationTest's portica.kvm.encryption-key. */
    static final String KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    @Autowired
    JdbcClient jdbc;

    @Test
    void legacyValuesAreRewrappedAndStillServed() throws Exception {
        String alice = uniqueName("alice");
        String org = createOrganization(alice);
        mvc.perform(post("/api/v1/organizations/{org}/environments", org).with(person(alice))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"dev\"}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/organizations/{org}/ai/providers", org).with(person(alice))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"openai\",\"type\":\"OPENAI\",\"apiKey\":\"sk-new-format\",\"models\":[]}"))
                .andExpect(status().isCreated());
        // simulate a value written by Phase 7 (v1: format, the KVM key used directly)
        String orgId = jdbc.sql("SELECT id::text FROM organizations WHERE name = :n").param("n", org).query(String.class).single();
        jdbc.sql("UPDATE ai_providers SET api_key_encrypted = :v WHERE name = 'openai' AND organization_id = :o::uuid")
                .param("v", legacy("sk-old-format", "ai-provider:" + orgId + ":openai")).param("o", orgId).update();

        mvc.perform(get("/api/v1/platform/secrets").with(platformAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.backend").value(startsWith("local")))
                .andExpect(jsonPath("$.needingRewrap").value(greaterThanOrEqualTo(1)));
        mvc.perform(post("/api/v1/platform/secrets/rewrap").with(person(alice))).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/platform/secrets/rewrap").with(platformAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rewrapped").value(greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.failed").value(0));

        String stored = jdbc.sql("SELECT api_key_encrypted FROM ai_providers WHERE name = 'openai' AND organization_id = :o::uuid")
                .param("o", orgId).query(String.class).single();
        assertThat(stored).startsWith("s2:");
        mvc.perform(get("/internal/v1/runtime/organizations/{org}/environments/dev/ai", org).with(gateway()))
                .andExpect(jsonPath("$.providers[0].apiKey").value("sk-old-format"));
        mvc.perform(get("/api/v1/platform/secrets").with(platformAdmin()))
                .andExpect(jsonPath("$.needingRewrap").value(0));
        mvc.perform(get("/api/v1/platform/secrets").with(platformAdmin()))
                .andExpect(jsonPath("$.valuesByKey['local:default']").exists());
    }

    private static String legacy(String value, String aad) throws Exception {
        byte[] iv = new byte[12];
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(Base64.getDecoder().decode(KEY), "AES"), new GCMParameterSpec(128, iv));
        c.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
        byte[] ct = c.doFinal(value.getBytes(StandardCharsets.UTF_8));
        byte[] all = new byte[12 + ct.length];
        System.arraycopy(ct, 0, all, 12, ct.length);
        return "v1:" + Base64.getEncoder().encodeToString(all);
    }
}
