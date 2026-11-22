package shortener.cli;

import java.io.IOException;
import java.io.PipedReader;
import java.io.PipedWriter;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import shortener.core.Link;
import shortener.core.LinkService;
import shortener.core.State;
import shortener.core.StateStore;
import shortener.storage.FileStateStore;
import shortener.testing.TestClock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsoleTest {
    private static final Duration TTL = Duration.ofHours(1);
    private static final String FIRST_URL = "https://example.org/first";
    private static final String SECOND_URL = "https://example.org/second";

    @TempDir
    Path directory;
    private final TestClock clock = new TestClock();

    @Test
    void switchesProfilesAndProtectsForeignLinks() throws Exception {
        try (FileStateStore store = new FileStateStore(directory.resolve("state.properties"))) {
            List<URI> visited = new ArrayList<>();
            LinkService service = new LinkService(store, clock, visited::add, TTL, 3, 5);
            Link first = service.create(null, FIRST_URL, 2);
            Link second = service.create(null, SECOND_URL, 3);

            String output = run(service, """
                    help
                    use %s
                    list
                    edit short:%s - 1
                    delete short:%s
                    list
                    use %s
                    edit %s - 1
                    short:%s
                    open %s
                    exit
                    """.formatted(first.owner(), second.code(), second.code(), second.owner(),
                    second.code(), second.code(), second.code()));

            assertTrue(output.contains("short:" + first.code() + " -> " + FIRST_URL));
            assertFalse(output.contains("short:" + second.code() + " -> " + SECOND_URL));
            assertTrue(output.contains("может только её владелец"));
            assertTrue(output.contains("Профиль выбран: " + second.owner()));
            assertTrue(output.contains("Ссылка изменена: short:" + second.code()));
            assertTrue(output.contains("Переход выполнен: " + SECOND_URL));
            assertTrue(output.contains("Создайте новую ссылку"));
            assertEquals(List.of(URI.create(SECOND_URL)), visited);
        }
    }

    @Test
    void addsSeveralLinksAndStartsAnotherProfileOnDemand() throws Exception {
        try (FileStateStore store = new FileStateStore(directory.resolve("state.properties"))) {
            LinkService service = new LinkService(store, clock, uri -> {
            }, TTL, 3, 5);
            String output = run(service, """
                    add xyz
                    add https://example.org/first
                    add https://example.org/second 4
                    new
                    add https://example.org/first 1
                    exit
                    """);

            assertEquals(2, output.lines().filter(line -> line.startsWith("Ваш UUID: ")).count());
            assertEquals(3, output.lines().filter(line -> line.startsWith("Короткая ссылка: ")).distinct().count());
            State state = store.load();
            List<Long> linkCounts = state.users().stream()
                    .map(owner -> state.links().values().stream().filter(link -> link.owner().equals(owner)).count())
                    .sorted()
                    .toList();
            assertEquals(List.of(1L, 2L), linkCounts);
        }
    }

    @Test
    void scheduledCleanupPersistsExpirationWhileConsoleWaitsForInput() throws Exception {
        CountDownLatch expired = new CountDownLatch(1);
        try (FileStateStore fileStore = new FileStateStore(directory.resolve("state.properties"));
             PipedWriter writer = new PipedWriter();
             PipedReader reader = new PipedReader(writer)) {
            StateStore observedStore = new StateStore() {
                @Override
                public State load() throws IOException {
                    return fileStore.load();
                }

                @Override
                public void save(State state) throws IOException {
                    fileStore.save(state);
                    if (!state.retired().isEmpty()) {
                        expired.countDown();
                    }
                }
            };

            LinkService service = new LinkService(observedStore, clock, uri -> {
            }, TTL, 3, 5);
            Link link = service.create(null, FIRST_URL, 2);
            clock.advance(TTL);

            Console console = new Console(service, reader, new PrintWriter(new StringWriter()),
                    Duration.ofMillis(10));
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<?> running = executor.submit(() -> {
                    console.run();
                    return null;
                });
                assertTrue(expired.await(5, TimeUnit.SECONDS));
                assertFalse(fileStore.load().links().containsKey(link.code()));
                writer.write("exit\n");
                writer.flush();
                running.get(5, TimeUnit.SECONDS);
            } finally {
                executor.shutdownNow();
            }
        }
    }

    private static String run(LinkService service, String commands) throws IOException {
        StringWriter buffer = new StringWriter();
        new Console(service, new StringReader(commands), new PrintWriter(buffer), Duration.ofSeconds(30)).run();
        return buffer.toString();
    }
}
