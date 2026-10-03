package com.fixai.platform.fixcore;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import quickfix.ConfigError;
import quickfix.DataDictionary;

/**
 * Thread-safe cache of QuickFIX/J data dictionaries.
 *
 * <p>Bundled dictionaries are loaded from the QuickFIX/J classpath. Custom (broker-specific) dictionaries are
 * loaded from the filesystem and cached by absolute path. Callers must not mutate returned dictionaries.
 */
public final class DictionaryRegistry {

    private static final DictionaryRegistry SHARED = new DictionaryRegistry();

    private final Map<String, DataDictionary> cache = new ConcurrentHashMap<>();

    public static DictionaryRegistry shared() {
        return SHARED;
    }

    public DataDictionary application(FixVersion version) {
        return classpath(version.applicationDictionary());
    }

    public DataDictionary transport(FixVersion version) {
        return classpath(version.transportDictionary());
    }

    public DataDictionary classpath(String resource) {
        return cache.computeIfAbsent("classpath:" + resource, key -> load(resource));
    }

    public DataDictionary file(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        return cache.computeIfAbsent("file:" + absolute, key -> {
            try (InputStream in = Files.newInputStream(absolute)) {
                return new DataDictionary(in);
            } catch (IOException | ConfigError exception) {
                throw new FixDictionaryException("Unable to load FIX dictionary " + absolute.getFileName(), exception);
            }
        });
    }

    private static DataDictionary load(String resource) {
        ClassLoader loader = DictionaryRegistry.class.getClassLoader();
        try (InputStream in = loader.getResourceAsStream(resource)) {
            if (in == null) {
                throw new FixDictionaryException("FIX dictionary not found on classpath: " + resource, null);
            }
            return new DataDictionary(in);
        } catch (IOException | ConfigError exception) {
            throw new FixDictionaryException("Unable to load FIX dictionary " + resource, exception);
        }
    }

    /** Raised when a dictionary cannot be located or parsed. */
    public static final class FixDictionaryException extends RuntimeException {
        public FixDictionaryException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
