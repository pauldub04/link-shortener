package shortener.core;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

public record State(long nextId, Set<UUID> users, Map<String, Link> links, Map<String, RetiredLink> retired) {
    public State {
        if (nextId < 1) {
            throw new IllegalArgumentException("Счётчик кодов должен быть положительным");
        }
        users = Set.copyOf(users);
        links = Map.copyOf(links);
        retired = Map.copyOf(retired);
    }

    public static State empty() {
        return new State(1, Set.of(), Map.of(), Map.of());
    }
}
