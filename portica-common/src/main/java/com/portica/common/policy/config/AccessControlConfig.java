package com.portica.common.policy.config;

import java.util.List;

/**
 * AccessControl: allows or denies clients by IP address. Deny rules win; when {@code allow} is
 * non-empty, addresses outside it are denied.
 *
 * @param allow         IPs or CIDR ranges, e.g. {@code 10.0.0.0/8}
 * @param deny          IPs or CIDR ranges
 * @param sourceAddress template giving the address checked (default {@code {client.ip}})
 */
public record AccessControlConfig(List<String> allow, List<String> deny, String sourceAddress)
        implements PolicyConfig {

    public AccessControlConfig {
        allow = allow == null ? List.of() : List.copyOf(allow);
        deny = deny == null ? List.of() : List.copyOf(deny);
        sourceAddress = sourceAddress == null || sourceAddress.isBlank() ? "{client.ip}" : sourceAddress;
    }

    @Override
    public List<String> validate() {
        return new Problems()
                .require(!allow.isEmpty() || !deny.isEmpty(), "set 'allow' and/or 'deny'")
                .cidrs(allow, "allow").cidrs(deny, "deny").list();
    }
}
