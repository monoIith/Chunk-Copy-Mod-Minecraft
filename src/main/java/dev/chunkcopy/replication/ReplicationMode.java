package dev.chunkcopy.replication;

import java.util.Locale;

public enum ReplicationMode {
    LOADED,
    PERSISTENT;

    public static ReplicationMode parse(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }

    public String serializedName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
