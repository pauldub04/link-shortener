package shortener.storage;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import shortener.core.Link;
import shortener.core.LinkException;
import shortener.core.LinkService;
import shortener.core.LinkStatus;
import shortener.core.Notification;
import shortener.core.State;
import shortener.testing.TestClock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileStateStoreTest {
    private static final Duration TTL = Duration.ofHours(1);
    private static final String URL = "https://example.org/page?q=%D0%B4%D0%B0#title";

    @TempDir
    Path directory;
    private final TestClock clock = new TestClock();

    @Test
    void restartsWithSameOwnerAndContinuesVisitCount() throws Exception {
        Path path = directory.resolve("data/state.properties");
        Link link;
        try (FileStateStore store = new FileStateStore(path)) {
            LinkService service = service(store);
            link = service.create(null, URL, 2);
            service.open(link.code());
        }
        try (FileStateStore store = new FileStateStore(path)) {
            LinkService restored = service(store);

            assertTrue(restored.hasUser(link.owner()));
            Link active = restored.list(link.owner()).get(0);
            assertEquals(link.expiresAt(), active.expiresAt());
            assertEquals(URI.create(URL), active.url());
            assertEquals(1, active.visits());
            assertTrue(restored.open(link.code()).exhausted());
            assertNotEquals(link.code(), restored.create(link.owner(), URL, 2).code());
        }
    }

    @Test
    void startupRemovesExpiredLinkAndPreservesDeliveryAcrossRestart() throws Exception {
        Path path = directory.resolve("state.properties");
        Link link;
        try (FileStateStore store = new FileStateStore(path)) {
            link = service(store).create(null, URL, 3);
            clock.advance(TTL);
        }
        try (FileStateStore store = new FileStateStore(path)) {
            LinkService restored = service(store);

            assertFalse(store.load().links().containsKey(link.code()));
            assertEquals(List.of(new Notification(link.code(), LinkStatus.EXPIRED)),
                    restored.readNotifications(link.owner()));
        }
        try (FileStateStore store = new FileStateStore(path)) {
            LinkService restored = service(store);

            assertTrue(restored.readNotifications(link.owner()).isEmpty());
            LinkException exception = assertThrows(LinkException.class, () -> restored.open(link.code()));
            assertEquals(LinkException.Reason.EXPIRED, exception.getReason());
            assertFalse(Files.readString(path).contains("example.org"));
        }
    }

    @Test
    void forbidsSecondWriterAndReleasesLockOnClose() throws IOException {
        Path path = directory.resolve("state.properties");
        try (FileStateStore store = new FileStateStore(path)) {
            assertEquals(State.empty(), store.load());
            assertThrows(IOException.class, () -> new FileStateStore(path));
        }
        try (FileStateStore reopened = new FileStateStore(path)) {
            assertEquals(State.empty(), reopened.load());
        }
    }

    @Test
    void invalidStateIsNotSilentlyReset() throws IOException {
        Path path = Files.writeString(directory.resolve("state.properties"), "version=1\nnextId=-1\n");
        try (FileStateStore store = new FileStateStore(path)) {
            assertThrows(IOException.class, store::load);
        }
        assertEquals("version=1\nnextId=-1\n", Files.readString(path));
    }

    private LinkService service(FileStateStore store) throws IOException {
        return new LinkService(store, clock, uri -> {
        }, TTL, 3, 10);
    }
}
