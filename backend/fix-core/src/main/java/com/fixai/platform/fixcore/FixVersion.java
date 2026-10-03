package com.fixai.platform.fixcore;

import java.util.Arrays;
import java.util.Optional;

/**
 * FIX protocol versions supported by the platform.
 *
 * <p>FIX 5.0 SP2 runs over the FIXT.1.1 transport; its session layer uses the FIXT11 dictionary and the
 * application layer uses the FIX50SP2 dictionary selected through {@code DefaultApplVerID(1137)=9}.
 */
public enum FixVersion {
    FIX42("FIX.4.2", "FIX42.xml", null, null),
    FIX44("FIX.4.4", "FIX44.xml", null, null),
    FIX50SP2("FIXT.1.1", "FIX50SP2.xml", "FIXT11.xml", "9");

    private final String beginString;
    private final String applicationDictionary;
    private final String transportDictionary;
    private final String defaultApplVerId;

    FixVersion(String beginString, String applicationDictionary, String transportDictionary, String defaultApplVerId) {
        this.beginString = beginString;
        this.applicationDictionary = applicationDictionary;
        this.transportDictionary = transportDictionary;
        this.defaultApplVerId = defaultApplVerId;
    }

    public String beginString() {
        return beginString;
    }

    /** Classpath resource of the application-level dictionary bundled with QuickFIX/J. */
    public String applicationDictionary() {
        return applicationDictionary;
    }

    /** Classpath resource of the session-level dictionary; equals the application dictionary before FIXT. */
    public String transportDictionary() {
        return transportDictionary == null ? applicationDictionary : transportDictionary;
    }

    public boolean isFixt() {
        return transportDictionary != null;
    }

    public Optional<String> defaultApplVerId() {
        return Optional.ofNullable(defaultApplVerId);
    }

    /** Resolves a version from its FIX BeginString, treating FIXT.1.1 as FIX 5.0 SP2. */
    public static Optional<FixVersion> fromBeginString(String beginString) {
        return Arrays.stream(values()).filter(v -> v.beginString.equals(beginString)).findFirst();
    }

    /** Resolves a version from either the enum name (e.g. {@code FIX44}) or the BeginString. */
    public static FixVersion parse(String value) {
        for (FixVersion version : values()) {
            if (version.name().equalsIgnoreCase(value) || version.beginString.equalsIgnoreCase(value)) {
                return version;
            }
        }
        throw new IllegalArgumentException("Unsupported FIX version: " + value);
    }
}
