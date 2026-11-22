package shortener.storage;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;

public record AppConfig(Duration ttl, int defaultLimit, int maxLimit, Duration cleanupInterval, Path dataPath) {
    public static AppConfig load(Path configPath) throws IOException {
        Path path = configPath.toAbsolutePath().normalize();
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IllegalArgumentException e) {
            throw new IOException("Неверный формат конфигурации " + path, e);
        }

        try {
            Duration ttl = Duration.ofMillis(positiveLong(properties, "link.ttl.millis"));
            int defaultLimit = positiveInt(properties, "link.limit.default");
            int maxLimit = positiveInt(properties, "link.limit.max");
            if (defaultLimit > maxLimit) {
                throw new IllegalArgumentException("link.limit.default должен быть не больше link.limit.max");
            }
            Duration cleanupInterval = Duration.ofMillis(positiveLong(properties, "cleanup.interval.millis"));
            Path dataPath = path.getParent().resolve(required(properties, "storage.path")).normalize();
            return new AppConfig(ttl, defaultLimit, maxLimit, cleanupInterval, dataPath);
        } catch (IllegalArgumentException e) {
            throw new IOException("Неверная настройка в " + path + ": " + e.getMessage(), e);
        }
    }

    private static long positiveLong(Properties properties, String key) {
        String value = required(properties, key);
        String message = key + " должен содержать положительное целое число";
        long number;
        try {
            number = Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(message, e);
        }
        if (number <= 0) {
            throw new IllegalArgumentException(message);
        }
        return number;
    }

    private static int positiveInt(Properties properties, String key) {
        long number = positiveLong(properties, key);
        if (number > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(key + " превышает допустимое значение " + Integer.MAX_VALUE);
        }
        return (int) number;
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Задайте " + key);
        }
        return value.strip();
    }
}
