package shortener.core;

import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import shortener.core.LinkException.Reason;

public final class LinkService {
    private static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final int MIN_CODE_LENGTH = 6;

    private final StateStore store;
    private final Clock clock;
    private final Browser browser;
    private final Duration ttl;
    private final int defaultLimit;
    private final int maxLimit;
    private State state;

    public LinkService(StateStore store, Clock clock, Browser browser, Duration ttl,
                       int defaultLimit, int maxLimit) throws IOException {
        if (ttl.isZero() || ttl.isNegative() || defaultLimit < 1 || defaultLimit > maxLimit) {
            throw new IllegalArgumentException("Некорректные настройки TTL или лимита переходов");
        }
        this.store = store;
        this.clock = clock;
        this.browser = browser;
        this.ttl = ttl;
        this.defaultLimit = defaultLimit;
        this.maxLimit = maxLimit;
        state = store.load();
        purgeExpired();
    }

    public synchronized Link create(UUID owner, String url, Integer limit) throws IOException, LinkException {
        URI target = parseUrl(url);
        int visitLimit = limit == null ? defaultLimit : limit;
        validateLimit(visitLimit, 0);
        if (state.nextId() == Long.MAX_VALUE) {
            throw new LinkException(Reason.CODE_SPACE_EXHAUSTED, "Свободные коды закончились");
        }
        String code = encode(state.nextId());
        if (state.links().containsKey(code) || state.retired().containsKey(code)) {
            throw new IOException("Счётчик кодов в хранилище указывает на занятую ссылку");
        }

        Set<UUID> users = state.users();
        if (owner == null) {
            users = new HashSet<>(users);
            do {
                owner = UUID.randomUUID();
            } while (!users.add(owner));
        } else {
            requireUser(owner);
        }

        Link link = new Link(code, owner, target, clock.instant().plus(ttl), visitLimit, 0);
        Map<String, Link> links = new HashMap<>(state.links());
        links.put(code, link);
        commit(new State(state.nextId() + 1, users, links, state.retired()));
        return link;
    }

    public synchronized boolean hasUser(UUID owner) {
        return owner != null && state.users().contains(owner);
    }

    public synchronized List<Link> list(UUID owner) throws IOException, LinkException {
        requireUser(owner);
        purgeExpired();
        return state.links().values().stream()
                .filter(link -> link.owner().equals(owner))
                .sorted(Comparator.comparing(Link::code))
                .toList();
    }

    public synchronized Link edit(UUID owner, String code, String url, Integer limit) throws IOException, LinkException {
        Link link = requireOwnedLink(owner, code);
        if (url == null && limit == null) {
            throw new LinkException(Reason.INVALID_EDIT, "Укажите новый URL или лимит");
        }
        URI target = url == null ? link.url() : parseUrl(url);
        int visitLimit = link.limit();
        if (limit != null) {
            visitLimit = limit;
            validateLimit(visitLimit, link.visits());
        }
        Link edited = new Link(code, owner, target, link.expiresAt(), visitLimit, link.visits());
        saveLink(edited);
        return edited;
    }

    public synchronized void delete(UUID owner, String code) throws IOException, LinkException {
        requireOwnedLink(owner, code);
        Map<String, Link> links = new HashMap<>(state.links());
        links.remove(code);
        commit(new State(state.nextId(), state.users(), links, state.retired()));
    }

    public synchronized OpenResult open(String code) throws IOException, LinkException {
        Link link = requireActive(findLink(code));
        State beforeVisit = state;
        int visits = link.visits() + 1;
        boolean exhausted = visits == link.limit();
        if (exhausted) {
            retireLinks(List.of(link), LinkStatus.EXHAUSTED);
        } else {
            saveLink(new Link(code, link.owner(), link.url(), link.expiresAt(), link.limit(), visits));
        }

        try {
            browser.open(link.url());
        } catch (IOException | RuntimeException exception) {
            try {
                commit(beforeVisit);
            } catch (IOException rollbackError) {
                IOException failure = new IOException(
                        "Браузер не открылся, а счётчик восстановить не удалось. Переход остался учтённым",
                        exception);
                failure.addSuppressed(rollbackError);
                throw failure;
            }
            throw exception;
        }
        return new OpenResult(link.url(), exhausted);
    }

    public synchronized int purgeExpired() throws IOException {
        Instant now = clock.instant();
        List<Link> expired = state.links().values().stream()
                .filter(link -> link.isExpired(now))
                .toList();
        if (!expired.isEmpty()) {
            retireLinks(expired, LinkStatus.EXPIRED);
        }
        return expired.size();
    }

    public synchronized List<Notification> readNotifications(UUID owner) throws IOException, LinkException {
        requireUser(owner);
        purgeExpired();
        List<Notification> notifications = state.retired().entrySet().stream()
                .filter(entry -> entry.getValue().owner().equals(owner) && !entry.getValue().notified())
                .map(entry -> new Notification(entry.getKey(), entry.getValue().status()))
                .sorted(Comparator.comparing(Notification::code))
                .toList();
        if (!notifications.isEmpty()) {
            Map<String, RetiredLink> retired = new HashMap<>(state.retired());
            for (Notification notification : notifications) {
                retired.put(notification.code(), new RetiredLink(owner, notification.status(), true));
            }
            commit(new State(state.nextId(), state.users(), state.links(), retired));
        }
        return notifications;
    }

    private Link requireOwnedLink(UUID owner, String code) throws IOException, LinkException {
        requireUser(owner);
        Link link = findLink(code);
        if (!link.owner().equals(owner)) {
            throw new LinkException(Reason.ACCESS_DENIED, "Изменять или удалять ссылку может только её владелец");
        }
        return requireActive(link);
    }

    private Link findLink(String code) throws LinkException {
        if (code != null) {
            Link link = state.links().get(code);
            if (link != null) {
                return link;
            }
            RetiredLink retired = state.retired().get(code);
            if (retired != null) {
                throw unavailable(retired.status());
            }
        }
        throw new LinkException(Reason.NOT_FOUND, "Ссылка не найдена");
    }

    private Link requireActive(Link link) throws IOException, LinkException {
        if (link.isExpired(clock.instant())) {
            retireLinks(List.of(link), LinkStatus.EXPIRED);
            throw unavailable(LinkStatus.EXPIRED);
        }
        return link;
    }

    private void requireUser(UUID owner) throws LinkException {
        if (!hasUser(owner)) {
            throw new LinkException(Reason.UNKNOWN_USER, "Сначала создайте ссылку или выберите профиль командой use");
        }
    }

    private void validateLimit(int limit, int visits) throws LinkException {
        if (limit < 1 || limit > maxLimit) {
            throw new LinkException(Reason.INVALID_LIMIT, "Лимит переходов должен быть от 1 до " + maxLimit);
        }
        if (limit <= visits) {
            throw new LinkException(Reason.INVALID_LIMIT,
                    "Лимит должен превышать число выполненных переходов (" + visits + ")");
        }
    }

    private void saveLink(Link link) throws IOException {
        Map<String, Link> links = new HashMap<>(state.links());
        links.put(link.code(), link);
        commit(new State(state.nextId(), state.users(), links, state.retired()));
    }

    private void retireLinks(List<Link> linksToRetire, LinkStatus status) throws IOException {
        Map<String, Link> links = new HashMap<>(state.links());
        Map<String, RetiredLink> retired = new HashMap<>(state.retired());
        for (Link link : linksToRetire) {
            links.remove(link.code());
            retired.put(link.code(), new RetiredLink(link.owner(), status, false));
        }
        commit(new State(state.nextId(), state.users(), links, retired));
    }

    private void commit(State updated) throws IOException {
        store.save(updated);
        state = updated;
    }

    private static LinkException unavailable(LinkStatus status) {
        return switch (status) {
            case EXPIRED -> new LinkException(Reason.EXPIRED, "Срок действия ссылки истёк. Создайте новую ссылку");
            case EXHAUSTED -> new LinkException(Reason.EXHAUSTED, "Лимит переходов исчерпан. Создайте новую ссылку");
        };
    }

    private static URI parseUrl(String url) throws LinkException {
        try {
            return Link.parseUrl(url);
        } catch (IllegalArgumentException e) {
            throw new LinkException(Reason.INVALID_URL, "Введите полный URL, например https://example.com/page");
        }
    }

    private static String encode(long id) {
        StringBuilder code = new StringBuilder();
        int base = ALPHABET.length();
        while (id > 0) {
            code.append(ALPHABET.charAt((int) (id % base)));
            id /= base;
        }
        while (code.length() < MIN_CODE_LENGTH) {
            code.append('0');
        }
        return code.reverse().toString();
    }
}
