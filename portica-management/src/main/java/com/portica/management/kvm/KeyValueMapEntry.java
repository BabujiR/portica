package com.portica.management.kvm;

import com.portica.management.shared.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.util.Objects;

/** One entry of a key value map. {@code storedValue} is ciphertext when the map is encrypted. */
@Entity
@Table(name = "key_value_map_entries")
public class KeyValueMapEntry extends AuditableEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "map_id", nullable = false, updatable = false)
    private KeyValueMap map;

    @Column(name = "entry_key", nullable = false, updatable = false, length = 255)
    private String key;

    @Column(name = "entry_value", nullable = false, columnDefinition = "text")
    private String storedValue;

    protected KeyValueMapEntry() {
        // JPA
    }

    KeyValueMapEntry(KeyValueMap map, String key, String storedValue) {
        this.map = Objects.requireNonNull(map, "map");
        this.key = Objects.requireNonNull(key, "key");
        this.storedValue = Objects.requireNonNull(storedValue, "storedValue");
    }

    void setStoredValue(String storedValue) {
        this.storedValue = Objects.requireNonNull(storedValue, "storedValue");
    }

    public KeyValueMap getMap() {
        return map;
    }

    public String getKey() {
        return key;
    }

    public String getStoredValue() {
        return storedValue;
    }
}
