package shortener.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AppConfigTest {
    @TempDir
    Path directory;

    @Test
    void loadsDurationsAndResolvesDataPathRelativeToConfig() throws IOException {
        Path config = writeConfig("""
                link.ttl.millis=90000
                link.limit.default=3
                link.limit.max=20
                cleanup.interval.millis=25
                storage.path=data/state.properties
                """);

        assertEquals(new AppConfig(Duration.ofSeconds(90), 3, 20, Duration.ofMillis(25),
                directory.resolve("data/state.properties")), AppConfig.load(config));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "link.ttl.millis=0",
            "link.limit.default=21",
            "link.limit.max=2147483648",
            "cleanup.interval.millis=not-a-number",
            "storage.path="
    })
    void rejectsInvalidSettings(String override) throws IOException {
        Path config = writeConfig("""
                link.ttl.millis=90000
                link.limit.default=3
                link.limit.max=20
                cleanup.interval.millis=25
                storage.path=data/state.properties
                """ + override);

        assertThrows(IOException.class, () -> AppConfig.load(config));
    }

    private Path writeConfig(String content) throws IOException {
        return Files.writeString(directory.resolve("application.properties"), content);
    }
}
