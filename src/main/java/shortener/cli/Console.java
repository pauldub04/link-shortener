package shortener.cli;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Reader;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import shortener.core.Link;
import shortener.core.LinkException;
import shortener.core.LinkService;
import shortener.core.Notification;
import shortener.core.OpenResult;

public final class Console {
    private static final String PREFIX = "short:";
    private static final Pattern CODE = Pattern.compile("[0-9A-Za-z]+");
    private static final String HELP = """
            add <url> [лимит]                 Сократить HTTP(S)-адрес
            open <код|short:код>              Открыть исходный URL в браузере
            short:код                        Открыть ссылку без команды open
            list                             Показать активные ссылки вашего профиля
            edit <код|short:код> <url|-> <лимит|->
                                             Изменить URL или лимит (- сохраняет параметр)
            delete <код|short:код>            Удалить свою ссылку
            use <uuid>                       Вернуться к существующему профилю
            new                              Начать новый профиль
            help                             Показать команды
            exit                             Выйти
            """;

    private final LinkService service;
    private final BufferedReader input;
    private final PrintWriter output;
    private final long cleanupIntervalMillis;
    private UUID owner;

    public Console(LinkService service, Reader input, PrintWriter output, Duration cleanupInterval) {
        this.service = service;
        this.input = new BufferedReader(input);
        this.output = output;
        cleanupIntervalMillis = cleanupInterval.toMillis();
        if (cleanupIntervalMillis <= 0) {
            throw new IllegalArgumentException("Интервал очистки должен быть не меньше 1 мс.");
        }
    }

    public void run() throws IOException {
        ScheduledExecutorService cleaner = Executors.newSingleThreadScheduledExecutor();
        try {
            output.println("Короткие ссылки. Команды: help");
            output.flush();

            cleaner.scheduleWithFixedDelay(() -> cleanUp(cleaner), cleanupIntervalMillis,
                    cleanupIntervalMillis, TimeUnit.MILLISECONDS);

            String line;
            while ((line = input.readLine()) != null) {
                if (!handle(line.strip())) {
                    break;
                }
            }
        } finally {
            synchronized (this) {
                cleaner.shutdown();
            }
        }
    }

    private synchronized boolean handle(String line) {
        boolean running = true;
        try {
            if (!line.isEmpty()) {
                running = execute(line.split("\\s+"));
            }
        } catch (IOException | LinkException | IllegalArgumentException e) {
            printError(e);
        }
        try {
            showNotifications();
        } catch (IOException | LinkException e) {
            printError(e);
        }
        output.flush();
        return running;
    }

    private synchronized void cleanUp(ScheduledExecutorService cleaner) {
        if (cleaner.isShutdown()) {
            return;
        }
        try {
            if (owner == null) {
                service.purgeExpired();
            } else {
                showNotifications();
            }
        } catch (IOException | LinkException e) {
            printError(e);
        }
        output.flush();
    }

    private boolean execute(String[] args) throws IOException, LinkException {
        if (args.length == 1 && args[0].startsWith(PREFIX)) {
            open(args[0]);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "add" -> add(args);
            case "open" -> {
                requireCount(args, 2, "open <код|short:код>");
                open(args[1]);
            }
            case "list" -> list(args);
            case "edit" -> edit(args);
            case "delete" -> delete(args);
            case "use" -> use(args);
            case "new" -> {
                requireCount(args, 1, "new");
                owner = null;
                output.println("Новый UUID появится при создании первой ссылки.");
            }
            case "help" -> {
                requireCount(args, 1, "help");
                output.print(HELP);
            }
            case "exit" -> {
                requireCount(args, 1, "exit");
                return false;
            }
            default -> output.println("Неизвестная команда. Введите help для списка команд.");
        }
        return true;
    }

    private void add(String[] args) throws LinkException, IOException {
        if (args.length < 2 || args.length > 3) {
            throw new IllegalArgumentException("Формат команды: add <url> [лимит]");
        }
        Integer limit = args.length == 3 ? parseLimit(args[2]) : null;
        Link link = service.create(owner, args[1], limit);
        if (owner == null) {
            owner = link.owner();
            output.println("Ваш UUID: " + owner);
        }
        output.println("Короткая ссылка: " + PREFIX + link.code());
    }

    private void open(String reference) throws LinkException, IOException {
        OpenResult result = service.open(parseCode(reference));
        String suffix = result.exhausted() ? " (последний разрешённый переход)" : "";
        output.println("Переход выполнен: " + result.url() + suffix);
    }

    private void list(String[] args) throws LinkException, IOException {
        requireCount(args, 1, "list");
        List<Link> links = service.list(currentOwner());
        if (links.isEmpty()) {
            output.println("Активных ссылок нет.");
        }
        for (Link link : links) {
            output.println(PREFIX + link.code() + " -> " + link.url()
                    + " (переходы: " + link.visits() + "/" + link.limit()
                    + ", действует до " + link.expiresAt() + ")");
        }
    }

    private void edit(String[] args) throws LinkException, IOException {
        requireCount(args, 4, "edit <код|short:код> <url|-> <лимит|->");
        UUID currentOwner = currentOwner();
        String code = parseCode(args[1]);
        String url = args[2].equals("-") ? null : args[2];
        Integer limit = args[3].equals("-") ? null : parseLimit(args[3]);
        Link link = service.edit(currentOwner, code, url, limit);
        output.println("Ссылка изменена: " + PREFIX + link.code());
    }

    private void delete(String[] args) throws LinkException, IOException {
        requireCount(args, 2, "delete <код|short:код>");
        String code = parseCode(args[1]);
        service.delete(currentOwner(), code);
        output.println("Ссылка удалена: " + PREFIX + code);
    }

    private void use(String[] args) {
        requireCount(args, 2, "use <uuid>");
        UUID selected;
        try {
            selected = UUID.fromString(args[1]);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Введите UUID профиля целиком.", e);
        }
        if (!selected.toString().equalsIgnoreCase(args[1])) {
            throw new IllegalArgumentException("Введите UUID профиля целиком.");
        }
        if (!service.hasUser(selected)) {
            throw new IllegalArgumentException("Профиль не найден.");
        }
        owner = selected;
        output.println("Профиль выбран: " + owner);
    }

    private void showNotifications() throws IOException, LinkException {
        if (owner == null) {
            return;
        }
        for (Notification event : service.readNotifications(owner)) {
            String link = PREFIX + event.code();
            String reason = switch (event.status()) {
                case EXPIRED -> "Срок действия ссылки " + link + " истёк.";
                case EXHAUSTED -> "Лимит переходов по ссылке " + link + " исчерпан.";
            };
            output.println(reason + " Создайте новую ссылку командой add.");
        }
    }

    private UUID currentOwner() {
        if (owner == null) {
            throw new IllegalArgumentException("Сначала создайте ссылку через add или выберите профиль через use.");
        }
        return owner;
    }

    private static String parseCode(String argument) {
        String code = argument.startsWith(PREFIX) ? argument.substring(PREFIX.length()) : argument;
        if (!CODE.matcher(code).matches()) {
            throw new IllegalArgumentException("Введите код или короткую ссылку в формате short:код.");
        }
        return code;
    }

    private static int parseLimit(String argument) {
        try {
            return Integer.parseInt(argument);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Лимит должен быть целым числом.", e);
        }
    }

    private static void requireCount(String[] args, int count, String usage) {
        if (args.length != count) {
            throw new IllegalArgumentException("Формат команды: " + usage);
        }
    }

    private void printError(Exception e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        output.println("Ошибка: " + message);
    }
}
