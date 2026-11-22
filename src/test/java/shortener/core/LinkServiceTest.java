package shortener.core;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import shortener.core.LinkException.Reason;
import shortener.testing.TestClock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LinkServiceTest {
    private static final String URL = "https://example.org/articles/java?language=ru#intro";
    private static final Duration TTL = Duration.ofHours(1);

    private final TestClock clock = new TestClock();
    private final MemoryStore store = new MemoryStore();
    private final List<URI> opened = new ArrayList<>();
    private LinkService service;

    @BeforeEach
    void setUp() throws IOException {
        service = new LinkService(store, clock, opened::add, TTL, 3, 5);
    }

    @Test
    void sameUrlGetsDifferentCodesForDifferentOwners() throws Exception {
        Link first = service.create(null, URL, null);
        Link second = service.create(null, URL, null);

        assertNotEquals(first.code(), second.code());
        assertNotEquals(first.owner(), second.owner());
        assertTrue(service.hasUser(first.owner()));
        assertEquals(List.of(first), service.list(first.owner()));
        assertEquals(List.of(second), service.list(second.owner()));
    }

    @Test
    void oneOwnerCanCreateSeveralLinks() throws Exception {
        Link first = service.create(null, URL, null);
        Link second = service.create(first.owner(), "http://example.org/", 5);

        assertEquals(3, first.limit());
        assertEquals(5, second.limit());
        assertEquals(List.of(first, second), service.list(first.owner()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "file:///tmp/page", "https:///missing-host", "https://example.org/a b",
            "https://user@example.org/", "https://example.org:65536/"})
    void rejectsInvalidUrl(String url) {
        assertReason(Reason.INVALID_URL, () -> service.create(null, url, null));
        assertEquals(State.empty(), store.state);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 6})
    void rejectsLimitOutsideConfiguration(int limit) {
        assertReason(Reason.INVALID_LIMIT, () -> service.create(null, URL, limit));
    }

    @Test
    void lastAllowedVisitSucceedsAndNextVisitFails() throws Exception {
        Link link = service.create(null, URL, 2);

        assertFalse(service.open(link.code()).exhausted());
        assertTrue(service.open(link.code()).exhausted());
        assertReason(Reason.EXHAUSTED, () -> service.open(link.code()));
        assertEquals(List.of(URI.create(URL), URI.create(URL)), opened);
        assertTrue(service.list(link.owner()).isEmpty());
    }

    @Test
    void countsConcurrentVisitsWithoutExceedingLimit() throws Exception {
        Link link = service.create(null, URL, 3);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(4);

        try {
            List<Future<Boolean>> attempts = new ArrayList<>();
            for (int index = 0; index < 8; index++) {
                attempts.add(executor.submit(() -> {
                    start.await();
                    try {
                        service.open(link.code());
                        return true;
                    } catch (LinkException exception) {
                        assertEquals(Reason.EXHAUSTED, exception.getReason());
                        return false;
                    }
                }));
            }
            start.countDown();

            int successes = 0;
            for (Future<Boolean> attempt : attempts) {
                if (attempt.get(5, TimeUnit.SECONDS)) {
                    successes++;
                }
            }
            assertEquals(3, successes);
            assertEquals(3, opened.size());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void browserFailureRestoresTheLastAvailableVisit() throws Exception {
        AtomicBoolean browserUnavailable = new AtomicBoolean(true);
        LinkService retryable = new LinkService(store, clock, uri -> {
            if (browserUnavailable.getAndSet(false)) {
                throw new IOException("Browser unavailable");
            }
            opened.add(uri);
        }, TTL, 3, 5);
        Link link = retryable.create(null, URL, 1);

        assertThrows(IOException.class, () -> retryable.open(link.code()));
        assertEquals(List.of(link), retryable.list(link.owner()));
        assertTrue(retryable.readNotifications(link.owner()).isEmpty());

        assertTrue(retryable.open(link.code()).exhausted());
        assertEquals(List.of(URI.create(URL)), opened);
    }

    @Test
    void saveFailurePreventsBrowserFromOpening() throws Exception {
        Link link = service.create(null, URL, 1);
        store.failNextSave = true;

        assertThrows(IOException.class, () -> service.open(link.code()));
        assertTrue(opened.isEmpty());
        assertEquals(List.of(link), service.list(link.owner()));
        assertTrue(service.open(link.code()).exhausted());
    }

    @Test
    void reportsReservedVisitWhenBrowserAndRollbackBothFail() throws Exception {
        LinkService failing = new LinkService(store, clock, uri -> {
            store.failNextSave = true;
            throw new IOException("Browser unavailable");
        }, TTL, 3, 5);
        Link link = failing.create(null, URL, 1);

        IOException error = assertThrows(IOException.class, () -> failing.open(link.code()));
        assertTrue(error.getMessage().contains("Переход остался учтённым"));
        assertEquals("Browser unavailable", error.getCause().getMessage());
        assertReason(Reason.EXHAUSTED, () -> failing.open(link.code()));
    }

    @Test
    void exactExpirationBoundaryBlocksVisit() throws Exception {
        Link link = service.create(null, URL, 3);
        clock.advance(TTL);

        assertReason(Reason.EXPIRED, () -> service.open(link.code()));
        assertTrue(service.list(link.owner()).isEmpty());
        assertTrue(opened.isEmpty());
    }

    @Test
    void purgeRemovesOnlyExpiredLinksAndReturnsNumberRemoved() throws Exception {
        Link first = service.create(null, URL, null);
        clock.advance(Duration.ofSeconds(1));
        Link second = service.create(first.owner(), URL, null);
        clock.advance(TTL.minusSeconds(1));

        assertEquals(1, service.purgeExpired());
        assertEquals(0, service.purgeExpired());
        assertEquals(List.of(second), service.list(first.owner()));
        assertReason(Reason.EXPIRED, () -> service.open(first.code()));
    }

    @Test
    void notificationsAreDeliveredOnceAndOnlyToOwner() throws Exception {
        Link exhausted = service.create(null, URL, 1);
        Link expired = service.create(exhausted.owner(), URL, 3);
        Link otherOwner = service.create(null, URL, 3);
        service.open(exhausted.code());

        assertTrue(service.readNotifications(otherOwner.owner()).isEmpty());
        clock.advance(TTL);
        assertEquals(List.of(
                new Notification(exhausted.code(), LinkStatus.EXHAUSTED),
                new Notification(expired.code(), LinkStatus.EXPIRED)
        ), service.readNotifications(exhausted.owner()));
        assertTrue(service.readNotifications(exhausted.owner()).isEmpty());
    }

    @Test
    void anotherOwnerCannotEditOrDeleteLink() throws Exception {
        Link link = service.create(null, URL, 3);
        Link other = service.create(null, URL, 3);

        assertReason(Reason.ACCESS_DENIED, () -> service.edit(other.owner(), link.code(), null, 4));
        assertReason(Reason.ACCESS_DENIED, () -> service.delete(other.owner(), link.code()));
        assertEquals(List.of(link), service.list(link.owner()));
    }

    @Test
    void editingPreservesVisitCountAndExpiration() throws Exception {
        Link link = service.create(null, URL, 3);
        service.open(link.code());
        clock.advance(Duration.ofMinutes(5));

        Link edited = service.edit(link.owner(), link.code(), "http://example.net/path", 2);

        assertEquals(link.expiresAt(), edited.expiresAt());
        assertEquals(1, edited.visits());
        assertEquals(2, edited.limit());
        assertEquals(URI.create("http://example.net/path"), service.open(link.code()).url());
        assertReason(Reason.EXHAUSTED, () -> service.open(link.code()));
    }

    @Test
    void editedLimitMustExceedNumberOfVisits() throws Exception {
        Link link = service.create(null, URL, 3);
        service.open(link.code());

        assertReason(Reason.INVALID_LIMIT, () -> service.edit(link.owner(), link.code(), null, 1));
        assertReason(Reason.INVALID_EDIT, () -> service.edit(link.owner(), link.code(), null, null));
        assertEquals(3, service.list(link.owner()).get(0).limit());
    }

    @Test
    void deletingLinkDoesNotReleaseItsCodeForReuse() throws Exception {
        Link link = service.create(null, URL, 3);
        service.delete(link.owner(), link.code());

        assertReason(Reason.NOT_FOUND, () -> service.open(link.code()));
        assertTrue(service.list(link.owner()).isEmpty());
        assertNotEquals(link.code(), service.create(link.owner(), URL, 3).code());
    }

    @Test
    void unknownOwnerCannotManageLinks() throws Exception {
        UUID unknown = new UUID(0, 0);
        Link link = service.create(null, URL, null);

        assertFalse(service.hasUser(unknown));
        assertReason(Reason.UNKNOWN_USER, () -> service.list(unknown));
        assertReason(Reason.UNKNOWN_USER, () -> service.create(unknown, URL, 3));
        assertReason(Reason.UNKNOWN_USER, () -> service.delete(unknown, link.code()));
    }

    @Test
    void failedCreateLeavesPreviousStateUnchanged() throws Exception {
        Link existing = service.create(null, URL, null);
        State before = store.state;
        store.failNextSave = true;

        assertThrows(IOException.class, () -> service.create(null, URL, null));
        assertEquals(List.of(existing), service.list(existing.owner()));
        assertEquals(before, store.state);
        Link next = service.create(existing.owner(), URL, null);
        assertNotEquals(existing.code(), next.code());
        assertEquals(before.nextId() + 1, store.state.nextId());
    }

    private static void assertReason(Reason reason, Executable action) {
        assertEquals(reason, assertThrows(LinkException.class, action).getReason());
    }

    private static class MemoryStore implements StateStore {
        private State state = State.empty();
        private boolean failNextSave;

        @Override
        public State load() {
            return state;
        }

        @Override
        public void save(State next) throws IOException {
            if (failNextSave) {
                failNextSave = false;
                throw new IOException("Сбой записи");
            }
            state = next;
        }
    }
}
