package com.portica.common.net;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.regex.Pattern;

/**
 * An IPv4 or IPv6 address range in CIDR notation ({@code 10.0.0.0/8}, {@code 2001:db8::/32}) or a
 * single address. Only IP literals are accepted; host names are rejected (no DNS lookups).
 */
public final class CidrRange {

    private static final Pattern IPV4 = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");
    private static final Pattern IPV6 = Pattern.compile("^[0-9A-Fa-f:.]+$");

    private final byte[] network;
    private final int prefix;
    private final String text;

    private CidrRange(byte[] network, int prefix, String text) {
        this.network = network;
        this.prefix = prefix;
        this.text = text;
    }

    public static CidrRange parse(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("empty address range");
        }
        String s = text.strip();
        int slash = s.indexOf('/');
        String address = slash < 0 ? s : s.substring(0, slash);
        byte[] bytes = literal(address).orElseThrow(() ->
                new IllegalArgumentException("'" + text + "' is not an IP address or CIDR range"));
        int max = bytes.length * 8;
        int prefix = max;
        if (slash >= 0) {
            try {
                prefix = Integer.parseInt(s.substring(slash + 1));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("'" + text + "' has an invalid prefix length");
            }
            if (prefix < 0 || prefix > max) {
                throw new IllegalArgumentException("'" + text + "' prefix must be between 0 and " + max);
            }
        }
        return new CidrRange(mask(bytes, prefix), prefix, s);
    }

    public boolean contains(String address) {
        return literal(address).map(this::contains).orElse(false);
    }

    public boolean contains(byte[] address) {
        if (address.length != network.length) {
            return false;
        }
        return java.util.Arrays.equals(mask(address, prefix), network);
    }

    /** Parses an IP literal without DNS; IPv4-mapped IPv6 addresses are converted by the JDK. */
    public static java.util.Optional<byte[]> literal(String address) {
        if (address == null) {
            return java.util.Optional.empty();
        }
        String a = address.strip();
        if (a.startsWith("[") && a.endsWith("]")) {
            a = a.substring(1, a.length() - 1);
        }
        int zone = a.indexOf('%');
        if (zone > 0) {
            a = a.substring(0, zone);
        }
        boolean v4 = IPV4.matcher(a).matches();
        boolean v6 = !v4 && a.contains(":") && IPV6.matcher(a).matches();
        if (!v4 && !v6) {
            return java.util.Optional.empty();
        }
        if (v4) {
            for (String part : a.split("\\.")) {
                if (Integer.parseInt(part) > 255) {
                    return java.util.Optional.empty();
                }
            }
        }
        try {
            return java.util.Optional.of(InetAddress.getByName(a).getAddress()); // literal: no DNS lookup
        } catch (UnknownHostException e) {
            return java.util.Optional.empty();
        }
    }

    private static byte[] mask(byte[] address, int prefix) {
        byte[] out = address.clone();
        for (int i = 0; i < out.length; i++) {
            int bitsInByte = Math.max(0, Math.min(8, prefix - i * 8));
            int m = bitsInByte == 0 ? 0 : (0xFF << (8 - bitsInByte)) & 0xFF;
            out[i] = (byte) (out[i] & m);
        }
        return out;
    }

    @Override
    public String toString() {
        return text;
    }
}
