package shortener.core;

import java.net.URI;

public record OpenResult(URI url, boolean exhausted) {
}
