package shortener.core;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

public record Link(String code, UUID owner, URI url, Instant expiresAt, int limit, int visits) {
    public boolean isExpired(Instant now) {
        return !expiresAt.isAfter(now);
    }

    public static URI parseUrl(String value) {
        URI url = URI.create(value == null ? "" : value);
        String scheme = url.getScheme();
        boolean http = "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
        int port = url.getPort();
        if (!http || url.getHost() == null || url.getRawUserInfo() != null || port == 0 || port > 65535) {
            throw new IllegalArgumentException("Нужен полный HTTP(S)-адрес");
        }
        return url;
    }
}
