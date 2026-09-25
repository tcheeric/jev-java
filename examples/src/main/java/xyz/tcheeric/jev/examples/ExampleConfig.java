package xyz.tcheeric.jev.examples;

import xyz.tcheeric.jev.JevConfig;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * How the examples find their API key and endpoint. This is example code, not library code:
 * the library never reads the environment, and your application should load its key from
 * wherever it keeps secrets.
 */
final class ExampleConfig {

    private ExampleConfig() {
    }

    /**
     * Reads {@code TYPESAFE_API_KEY}, or else the file named by {@code TYPESAFE_API_KEY_FILE},
     * and {@code TYPESAFE_BASE_URL} if set.
     */
    static JevConfig fromEnvironment() {
        String key = System.getenv("TYPESAFE_API_KEY");
        if (key == null || key.isBlank()) {
            String file = System.getenv("TYPESAFE_API_KEY_FILE");
            if (file == null || file.isBlank()) {
                throw new IllegalStateException(
                        "set TYPESAFE_API_KEY, or TYPESAFE_API_KEY_FILE to a file holding the key");
            }
            try {
                key = Files.readString(Path.of(file)).strip();
            } catch (IOException e) {
                throw new UncheckedIOException("cannot read TYPESAFE_API_KEY_FILE at " + file, e);
            }
        }
        String baseUrl = System.getenv("TYPESAFE_BASE_URL");
        return baseUrl == null || baseUrl.isBlank()
                ? JevConfig.of(key)
                : JevConfig.of(URI.create(baseUrl), key);
    }
}
