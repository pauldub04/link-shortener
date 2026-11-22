package shortener.storage;

import shortener.core.Link;
import shortener.core.LinkStatus;
import shortener.core.RetiredLink;
import shortener.core.State;
import shortener.core.StateStore;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public final class FileStateStore implements StateStore, AutoCloseable {
    private static final String STATE_VERSION = "1";
    private static final String USER_PREFIX = "user.";
    private static final String LINK_PREFIX = "link.";
    private static final String RETIRED_PREFIX = "retired.";
    private static final Pattern SHORT_CODE = Pattern.compile("[0-9A-Za-z]+");
    private static final Set<Path> OPEN_STORES = ConcurrentHashMap.newKeySet();

    private final Path path;
    private final Path lockPath;
    private final FileChannel lockChannel;

    public FileStateStore(Path dataPath) throws IOException {
        Path absolutePath = dataPath.toAbsolutePath().normalize();
        if (absolutePath.getFileName() == null) {
            throw new IOException("Укажите путь к файлу хранилища");
        }
        Path directory = Files.createDirectories(absolutePath.getParent()).toRealPath();
        path = directory.resolve(absolutePath.getFileName());
        lockPath = path.resolveSibling(path.getFileName() + ".lock");
        if (!OPEN_STORES.add(lockPath)) {
            throw new IOException(busyMessage(lockPath));
        }
        try {
            lockChannel = acquireLock(lockPath);
        } catch (IOException | RuntimeException e) {
            OPEN_STORES.remove(lockPath);
            throw e;
        }
    }

    @Override
    public synchronized State load() throws IOException {
        requireOpen();
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            properties.load(reader);
            return parseState(properties);
        } catch (NoSuchFileException e) {
            return State.empty();
        } catch (DateTimeException | IllegalArgumentException e) {
            throw new IOException("Повреждён файл хранилища " + path + ": " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized void save(State state) throws IOException {
        requireOpen();
        Properties properties = toProperties(state);
        Path temporary = Files.createTempFile(path.getParent(), ".shortener-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE);
                 Writer writer = new BufferedWriter(Channels.newWriter(channel, StandardCharsets.UTF_8))) {
                properties.store(writer, "Link shortener state");
                writer.flush();
                channel.force(true);
            }
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException e) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanupError) {
                e.addSuppressed(cleanupError);
            }
            throw e;
        }
    }

    @Override
    public synchronized void close() throws IOException {
        if (lockChannel.isOpen()) {
            try {
                lockChannel.close();
            } finally {
                OPEN_STORES.remove(lockPath);
            }
        }
    }

    private void requireOpen() throws IOException {
        if (!lockChannel.isOpen()) {
            throw new IOException("Хранилище уже закрыто");
        }
    }

    private static FileChannel acquireLock(Path lockPath) throws IOException {
        FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            if (channel.tryLock() == null) {
                throw new IOException(busyMessage(lockPath));
            }
            return channel;
        } catch (IOException | RuntimeException e) {
            try {
                channel.close();
            } catch (IOException closeError) {
                e.addSuppressed(closeError);
            }
            if (e instanceof OverlappingFileLockException) {
                throw new IOException(busyMessage(lockPath), e);
            }
            throw e;
        }
    }

    private static String busyMessage(Path lockPath) {
        return "Хранилище уже используется другим экземпляром: " + lockPath;
    }

    private static State parseState(Properties properties) {
        if (!STATE_VERSION.equals(properties.getProperty("version"))) {
            throw new IllegalArgumentException("неподдерживаемая версия формата");
        }
        long nextId = Long.parseLong(properties.getProperty("nextId"));
        Set<UUID> users = new HashSet<>();
        Map<String, Link> links = new HashMap<>();
        Map<String, RetiredLink> retired = new HashMap<>();
        for (String key : properties.stringPropertyNames()) {
            String value = properties.getProperty(key);
            if (key.startsWith(USER_PREFIX) && "true".equals(value)) {
                users.add(parseUuid(key.substring(USER_PREFIX.length())));
            } else if (key.startsWith(LINK_PREFIX)) {
                String code = readCode(key, LINK_PREFIX);
                links.put(code, readLink(code, value));
            } else if (key.startsWith(RETIRED_PREFIX)) {
                retired.put(readCode(key, RETIRED_PREFIX), readRetiredLink(value));
            } else if (!"version".equals(key) && !"nextId".equals(key)) {
                throw new IllegalArgumentException("неизвестная запись " + key);
            }
        }

        for (Link link : links.values()) {
            if (!users.contains(link.owner()) || retired.containsKey(link.code())) {
                throw new IllegalArgumentException("некорректная активная ссылка " + link.code());
            }
        }
        for (Map.Entry<String, RetiredLink> entry : retired.entrySet()) {
            if (!users.contains(entry.getValue().owner())) {
                throw new IllegalArgumentException("неизвестный владелец ссылки " + entry.getKey());
            }
        }
        return new State(nextId, users, links, retired);
    }

    private static Link readLink(String code, String value) {
        String[] fields = value.split("\t", -1);
        if (fields.length != 5) {
            throw new IllegalArgumentException("некорректная ссылка " + code);
        }
        UUID owner = parseUuid(fields[0]);
        int limit = Integer.parseInt(fields[1]);
        int visits = Integer.parseInt(fields[2]);
        Instant expiresAt = Instant.parse(fields[3]);
        if (limit < 1 || visits < 0 || visits >= limit) {
            throw new IllegalArgumentException("некорректная ссылка " + code);
        }
        return new Link(code, owner, Link.parseUrl(fields[4]), expiresAt, limit, visits);
    }

    private static RetiredLink readRetiredLink(String value) {
        String[] fields = value.split("\t", -1);
        if (fields.length != 3 || !("true".equals(fields[2]) || "false".equals(fields[2]))) {
            throw new IllegalArgumentException("некорректная запись о закрытой ссылке");
        }
        return new RetiredLink(parseUuid(fields[0]), LinkStatus.valueOf(fields[1]), Boolean.parseBoolean(fields[2]));
    }

    private static String readCode(String key, String prefix) {
        String code = key.substring(prefix.length());
        if (!SHORT_CODE.matcher(code).matches()) {
            throw new IllegalArgumentException("некорректный код " + code);
        }
        return code;
    }

    private static UUID parseUuid(String value) {
        UUID uuid = UUID.fromString(value);
        if (!uuid.toString().equalsIgnoreCase(value)) {
            throw new IllegalArgumentException("некорректный UUID владельца");
        }
        return uuid;
    }

    private static Properties toProperties(State state) {
        Properties properties = new Properties();
        properties.setProperty("version", STATE_VERSION);
        properties.setProperty("nextId", Long.toString(state.nextId()));
        for (UUID user : state.users()) {
            properties.setProperty(USER_PREFIX + user, "true");
        }
        for (Link link : state.links().values()) {
            properties.setProperty(LINK_PREFIX + link.code(), String.join("\t", link.owner().toString(),
                    Integer.toString(link.limit()), Integer.toString(link.visits()),
                    link.expiresAt().toString(), link.url().toString()));
        }
        for (Map.Entry<String, RetiredLink> entry : state.retired().entrySet()) {
            RetiredLink link = entry.getValue();
            properties.setProperty(RETIRED_PREFIX + entry.getKey(),
                    String.join("\t", link.owner().toString(), link.status().name(), Boolean.toString(link.notified())));
        }
        return properties;
    }
}
