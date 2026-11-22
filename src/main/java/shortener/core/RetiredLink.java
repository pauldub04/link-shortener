package shortener.core;

import java.util.UUID;

public record RetiredLink(UUID owner, LinkStatus status, boolean notified) {
}
