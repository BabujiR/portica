package com.portica.management.targetserver;

import java.util.Locale;

public enum TargetServerProtocol {
    HTTP,
    HTTPS;

    public String scheme() {
        return name().toLowerCase(Locale.ROOT);
    }
}
