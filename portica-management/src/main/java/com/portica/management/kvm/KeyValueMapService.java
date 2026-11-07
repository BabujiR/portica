package com.portica.management.kvm;

import com.portica.common.error.BusinessRuleViolationException;
import com.portica.common.error.InvalidRequestException;
import com.portica.common.policy.BundleValidator;
import com.portica.common.policy.config.KeyValueMapOperationsConfig;
import com.portica.common.error.ResourceConflictException;
import com.portica.common.error.ResourceNotFoundException;
import com.portica.management.audit.AuditService;
import com.portica.management.environment.Environment;
import com.portica.management.environment.EnvironmentFinder;
import com.portica.management.kvm.KeyValueMapDtos.CreateKeyValueMapRequest;
import com.portica.management.kvm.KeyValueMapDtos.EntryResponse;
import com.portica.management.kvm.KeyValueMapDtos.KeyValueMapResponse;
import com.portica.management.organization.Organization;
import com.portica.management.organization.OrganizationFinder;
import com.portica.management.runtime.RuntimeChanges;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Key value maps per environment ({@code env} given) or per organization ({@code env} null).
 * Values of encrypted maps are encrypted with {@link KvmCipher} and masked in every response;
 * only gateways receive them, through the authenticated runtime feed.
 */
@Service
public class KeyValueMapService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(KeyValueMapService.class);

    static final String RESOURCE_TYPE = "KeyValueMap";

    private final KeyValueMapRepository maps;
    private final KeyValueMapEntryRepository entries;
    private final OrganizationFinder organizations;
    private final EnvironmentFinder environments;
    private final KvmCipher cipher;
    private final RuntimeChanges runtimeChanges;
    private final AuditService audit;

    public KeyValueMapService(KeyValueMapRepository maps, KeyValueMapEntryRepository entries,
                              OrganizationFinder organizations, EnvironmentFinder environments, KvmCipher cipher,
                              RuntimeChanges runtimeChanges, AuditService audit) {
        this.maps = maps;
        this.entries = entries;
        this.organizations = organizations;
        this.environments = environments;
        this.cipher = cipher;
        this.runtimeChanges = runtimeChanges;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<KeyValueMapResponse> list(String org, String env) {
        Organization organization = organizations.getActive(org);
        List<KeyValueMap> list = env == null ? maps.findOrganizationMaps(organization.getId())
                : maps.findByEnvironment_IdOrderByName(environments.get(organization, env).getId());
        return list.stream().map(this::response).toList();
    }

    @Transactional(readOnly = true)
    public KeyValueMapResponse get(String org, String env, String name) {
        Organization organization = organizations.getActive(org);
        return response(find(organization, env, name));
    }

    @Transactional
    public KeyValueMapResponse create(String org, String env, CreateKeyValueMapRequest r) {
        Organization organization = organizations.getActive(org);
        Environment environment = env == null ? null : environments.get(organization, env);
        boolean exists = environment == null
                ? maps.findOrganizationMap(organization.getId(), r.name()).isPresent()
                : maps.findByEnvironment_IdAndName(environment.getId(), r.name()).isPresent();
        if (exists) {
            throw ResourceConflictException.alreadyExists(RESOURCE_TYPE, r.name());
        }
        boolean encrypted = Boolean.TRUE.equals(r.encrypted());
        if (encrypted) {
            cipher.requireEnabled();
        }
        KeyValueMap map = maps.save(new KeyValueMap(organization, environment, r.name(), encrypted));
        changed(organization, environment);
        audit.record(organization.getId(), "kvm.created", RESOURCE_TYPE, label(map), Map.of("encrypted", encrypted));
        return response(map);
    }

    @Transactional
    public void delete(String org, String env, String name) {
        Organization organization = organizations.getActive(org);
        KeyValueMap map = find(organization, env, name);
        maps.delete(map);
        changed(organization, map.getEnvironment());
        audit.record(organization.getId(), "kvm.deleted", RESOURCE_TYPE, label(map), Map.of());
    }

    @Transactional(readOnly = true)
    public Page<EntryResponse> listEntries(String org, String env, String name, Pageable pageable) {
        KeyValueMap map = find(organizations.getActive(org), env, name);
        return entries.findByMap_IdOrderByKey(map.getId(), pageable).map(e -> entryResponse(map, e));
    }

    @Transactional(readOnly = true)
    public EntryResponse getEntry(String org, String env, String name, String key) {
        KeyValueMap map = find(organizations.getActive(org), env, name);
        return entryResponse(map, findEntry(map, key));
    }

    /** Creates or replaces an entry. */
    @Transactional
    public EntryResponse putEntry(String org, String env, String name, String key, String value) {
        if (key == null || !key.matches(KeyValueMapDtos.ENTRY_KEY)) {
            throw new InvalidRequestException("Entry keys are 1-255 characters of letters, digits, '.', '_', ':' or '-'");
        }
        Organization organization = organizations.getActive(org);
        KeyValueMap map = find(organization, env, name);
        String stored = map.isEncrypted() ? cipher.encrypt(value, aad(map, key)) : value;
        KeyValueMapEntry entry = entries.findByMap_IdAndKey(map.getId(), key).orElse(null);
        boolean created = entry == null;
        if (created) {
            entry = entries.save(new KeyValueMapEntry(map, key, stored));
        } else {
            entry.setStoredValue(stored);
        }
        changed(organization, map.getEnvironment());
        audit.record(organization.getId(), created ? "kvm.entry.created" : "kvm.entry.updated", RESOURCE_TYPE,
                label(map), Map.of("key", key)); // never the value
        return entryResponse(map, entry);
    }

    @Transactional
    public void deleteEntry(String org, String env, String name, String key) {
        Organization organization = organizations.getActive(org);
        KeyValueMap map = find(organization, env, name);
        entries.delete(findEntry(map, key));
        changed(organization, map.getEnvironment());
        audit.record(organization.getId(), "kvm.entry.deleted", RESOURCE_TYPE, label(map), Map.of("key", key));
    }

    /** Maps a proxy in {@code environment} can read (environment scope and organization scope), with plain values. */
    @Transactional(readOnly = true)
    public List<RuntimeMap> runtimeMaps(Environment environment) {
        List<KeyValueMap> all = new java.util.ArrayList<>(maps.findByEnvironment_IdOrderByName(environment.getId()));
        all.addAll(maps.findOrganizationMaps(environment.getOrganization().getId()));
        if (all.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<KeyValueMapEntry>> byMap = entries.findByMap_IdIn(all.stream().map(KeyValueMap::getId).toList())
                .stream().collect(Collectors.groupingBy(e -> e.getMap().getId()));
        return all.stream().map(m -> {
            Map<String, String> values = new LinkedHashMap<>();
            for (KeyValueMapEntry e : byMap.getOrDefault(m.getId(), List.of())) {
                if (!m.isEncrypted()) {
                    values.put(e.getKey(), e.getStoredValue());
                    continue;
                }
                try {
                    values.put(e.getKey(), cipher.decrypt(e.getStoredValue(), aad(m, e.getKey())));
                } catch (RuntimeException ex) {
                    // Leave the entry out rather than failing the whole environment snapshot.
                    log.error("Key value map {} entry {} can't be decrypted and is left out: {}", label(m), e.getKey(),
                            ex.getMessage());
                }
            }
            return new RuntimeMap(m.getName(), m.scope(), m.isEncrypted(), values);
        }).toList();
    }

    /** Whether a map exists for a proxy deployed in {@code environment}; null when it doesn't. */
    @Transactional(readOnly = true)
    public KeyValueMap findForRuntime(Environment environment, String scope, String name) {
        return "organization".equals(scope)
                ? maps.findOrganizationMap(environment.getOrganization().getId(), name).orElse(null)
                : maps.findByEnvironment_IdAndName(environment.getId(), name).orElse(null);
    }

    /**
     * Deploy-time check for KeyValueMapOperations policies: every map read must exist for the
     * environment, and values of encrypted maps may only go to {@code private.} variables.
     */
    @Transactional(readOnly = true)
    public void requireUsable(Environment environment, List<BundleValidator.KvmReference> references) {
        List<String> problems = new java.util.ArrayList<>();
        for (BundleValidator.KvmReference ref : references) {
            KeyValueMap map = findForRuntime(environment, ref.scope(), ref.name());
            if (map == null) {
                problems.add("%s key value map '%s' does not exist%s".formatted(ref.scope(), ref.name(),
                        "environment".equals(ref.scope()) ? " in '" + environment.getName() + "'" : ""));
            } else if (map.isEncrypted()) {
                ref.assignTo().stream()
                        .filter(v -> !v.startsWith(KeyValueMapOperationsConfig.PRIVATE_PREFIX))
                        .forEach(v -> problems.add("key value map '%s' is encrypted: assign its values to private.* variables, not '%s'"
                                .formatted(ref.name(), v)));
            }
        }
        if (!problems.isEmpty()) {
            throw new BusinessRuleViolationException(String.join("; ", problems));
        }
    }

    public record RuntimeMap(String name, String scope, boolean encrypted, Map<String, String> entries) {
    }

    // ----------------------------------------------------------------- helpers

    private KeyValueMap find(Organization organization, String env, String name) {
        return (env == null
                ? maps.findOrganizationMap(organization.getId(), name)
                : maps.findByEnvironment_IdAndName(environments.get(organization, env).getId(), name))
                .orElseThrow(() -> new ResourceNotFoundException(RESOURCE_TYPE, name));
    }

    private KeyValueMapEntry findEntry(KeyValueMap map, String key) {
        return entries.findByMap_IdAndKey(map.getId(), key)
                .orElseThrow(() -> new ResourceNotFoundException("KeyValueMapEntry", key));
    }

    private void changed(Organization organization, Environment environment) {
        if (environment == null) {
            runtimeChanges.organizationChanged(organization);
        } else {
            runtimeChanges.environmentChanged(environment);
        }
    }

    private KeyValueMapResponse response(KeyValueMap m) {
        return new KeyValueMapResponse(m.getId(), m.getName(), m.scope(),
                m.getEnvironment() == null ? null : m.getEnvironment().getName(), m.isEncrypted(),
                entries.countByMap_Id(m.getId()), m.getCreatedAt(), m.getCreatedBy());
    }

    private static EntryResponse entryResponse(KeyValueMap map, KeyValueMapEntry e) {
        return new EntryResponse(e.getKey(), map.isEncrypted() ? KeyValueMapDtos.MASK : e.getStoredValue(),
                e.getUpdatedAt(), e.getUpdatedBy());
    }

    private static String aad(KeyValueMap map, String key) {
        return map.getId() + "/" + key;
    }

    private static String label(KeyValueMap map) {
        return (map.getEnvironment() == null ? "" : map.getEnvironment().getName() + "/") + map.getName();
    }
}
