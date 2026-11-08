package com.portica.management.ai;

import com.portica.management.ai.AiDtos.AiProviderResponse;
import com.portica.management.ai.AiDtos.AiProxyResponse;
import com.portica.management.ai.AiDtos.CreateAiProviderRequest;
import com.portica.management.ai.AiDtos.CreateAiProxyRequest;
import com.portica.management.ai.AiDtos.UpdateAiProviderRequest;
import com.portica.management.ai.AiDtos.UpdateAiProxyRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** AI gateway configuration: LLM providers and AI proxies (Phase 7). */
@RestController
@RequestMapping("/api/v1/organizations/{org}/ai")
@Tag(name = "AI gateway", description = "LLM providers and OpenAI-compatible AI proxies")
public class AiController {

    private final AiService ai;

    public AiController(AiService ai) {
        this.ai = ai;
    }

    @GetMapping("/providers")
    @PreAuthorize("@authz.hasPermission(#org, 'AI_READ')")
    @Operation(summary = "List LLM providers (API keys are never returned)")
    public List<AiProviderResponse> listProviders(@PathVariable String org) {
        return ai.listProviders(org);
    }

    @PostMapping("/providers")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@authz.hasPermission(#org, 'AI_WRITE')")
    @Operation(summary = "Add an LLM provider (OPENAI or ANTHROPIC, or any OpenAI-compatible server as OPENAI)")
    public AiProviderResponse createProvider(@PathVariable String org, @Valid @RequestBody CreateAiProviderRequest request) {
        return ai.createProvider(org, request);
    }

    @GetMapping("/providers/{name}")
    @PreAuthorize("@authz.hasPermission(#org, 'AI_READ')")
    public AiProviderResponse getProvider(@PathVariable String org, @PathVariable String name) {
        return ai.getProvider(org, name);
    }

    @PutMapping("/providers/{name}")
    @PreAuthorize("@authz.hasPermission(#org, 'AI_WRITE')")
    @Operation(summary = "Replace a provider's settings", description = "Omit apiKey to keep the stored key.")
    public AiProviderResponse updateProvider(@PathVariable String org, @PathVariable String name,
                                             @Valid @RequestBody UpdateAiProviderRequest request) {
        return ai.updateProvider(org, name, request);
    }

    @DeleteMapping("/providers/{name}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@authz.hasPermission(#org, 'AI_WRITE')")
    public void deleteProvider(@PathVariable String org, @PathVariable String name) {
        ai.deleteProvider(org, name);
    }

    @GetMapping("/proxies")
    @PreAuthorize("@authz.hasPermission(#org, 'AI_READ')")
    @Operation(summary = "List AI proxies")
    public List<AiProxyResponse> listProxies(@PathVariable String org) {
        return ai.listProxies(org);
    }

    @PostMapping("/proxies")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@authz.hasPermission(#org, 'AI_WRITE')")
    @Operation(summary = "Create an AI proxy",
            description = "Served at /{org}/{env}/ai/{name}/v1/chat/completions. Grant apps access by listing "
                    + "gatewayProxy (ai-{name}) in an API product.")
    public AiProxyResponse createProxy(@PathVariable String org, @Valid @RequestBody CreateAiProxyRequest request) {
        return ai.createProxy(org, request);
    }

    @GetMapping("/proxies/{name}")
    @PreAuthorize("@authz.hasPermission(#org, 'AI_READ')")
    public AiProxyResponse getProxy(@PathVariable String org, @PathVariable String name) {
        return ai.getProxy(org, name);
    }

    @PutMapping("/proxies/{name}")
    @PreAuthorize("@authz.hasPermission(#org, 'AI_WRITE')")
    public AiProxyResponse updateProxy(@PathVariable String org, @PathVariable String name,
                                       @Valid @RequestBody UpdateAiProxyRequest request) {
        return ai.updateProxy(org, name, request);
    }

    @DeleteMapping("/proxies/{name}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@authz.hasPermission(#org, 'AI_WRITE')")
    public void deleteProxy(@PathVariable String org, @PathVariable String name) {
        ai.deleteProxy(org, name);
    }
}
