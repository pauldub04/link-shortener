package shortener.cli;

import java.awt.Desktop;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.Reader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.TimeUnit;

import shortener.core.LinkService;
import shortener.storage.AppConfig;
import shortener.storage.FileStateStore;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        if (args.length > 1) {
            System.err.println("Укажите один путь к файлу конфигурации.");
            System.exit(2);
        }
        if (args.length == 1 && (args[0].equals("--help") || args[0].equals("-h"))) {
            System.out.println("Запуск: java -jar <jar-файл> [путь-к-application.properties]");
            return;
        }

        try {
            Path configPath = Path.of(args.length == 0 ? "application.properties" : args[0]);
            AppConfig config = AppConfig.load(configPath);
            try (FileStateStore store = new FileStateStore(config.dataPath())) {
                LinkService service = new LinkService(store, Clock.systemUTC(), Main::openBrowser,
                        config.ttl(), config.defaultLimit(), config.maxLimit());

                java.io.Console terminal = System.console();
                Reader input = terminal == null
                        ? new InputStreamReader(System.in, StandardCharsets.UTF_8) : terminal.reader();
                PrintWriter output = terminal == null
                        ? new PrintWriter(System.out, true, StandardCharsets.UTF_8) : terminal.writer();
                new Console(service, input, output, config.cleanupInterval()).run();
            }
        } catch (IOException | IllegalArgumentException e) {
            System.err.println("Ошибка приложения: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void openBrowser(URI uri) throws IOException {
        String unsupportedMessage = "Для команды open нужен рабочий стол с поддержкой запуска браузера.";
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop desktop = Desktop.getDesktop();
                if (desktop.isSupported(Desktop.Action.BROWSE)) {
                    desktop.browse(uri);
                    return;
                }
            }
            if ("Linux".equals(System.getProperty("os.name"))
                    && (System.getenv("DISPLAY") != null || System.getenv("WAYLAND_DISPLAY") != null)) {
                openWithGio(uri);
                return;
            }
        } catch (UnsupportedOperationException | SecurityException e) {
            throw new IOException(unsupportedMessage, e);
        }
        throw new IOException(unsupportedMessage);
    }

    private static void openWithGio(URI uri) throws IOException {
        Process launcher;
        try {
            launcher = new ProcessBuilder("gio", "open", uri.toASCIIString())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
        } catch (IOException e) {
            throw new IOException("Не удалось запустить gio open для открытия браузера.", e);
        }

        try {
            if (!launcher.waitFor(10, TimeUnit.SECONDS)) {
                launcher.destroyForcibly();
                throw new IOException("Истекло время ожидания запуска браузера.");
            }
        } catch (InterruptedException e) {
            launcher.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("Запуск браузера прерван.", e);
        }
        if (launcher.exitValue() != 0) {
            throw new IOException("Не удалось открыть ссылку в браузере по умолчанию.");
        }
    }
}
