package com.portica.management.policy;

import com.portica.common.policy.PolicyType;
import com.portica.common.policy.config.PolicyConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;

@RestController
@RequestMapping("/api/v1/policy-types")
@Tag(name = "Policy types", description = "Catalog of policies that can be attached to flows")
public class PolicyTypeController {

    @GetMapping
    @Operation(summary = "List policy types and their config fields",
            description = "Available to every authenticated user; the console uses it to build the policy palette.")
    public List<PolicyTypeResponse> list() {
        return Arrays.stream(PolicyType.values())
                .map(t -> new PolicyTypeResponse(t.typeName(), t.category(), t.description(), fields(t.configClass())))
                .toList();
    }

    private static List<String> fields(Class<? extends PolicyConfig> configClass) {
        RecordComponent[] components = configClass.getRecordComponents();
        return components == null ? List.of() : Arrays.stream(components).map(RecordComponent::getName).toList();
    }

    public record PolicyTypeResponse(String type, String category, String description, List<String> configFields) {
    }
}
