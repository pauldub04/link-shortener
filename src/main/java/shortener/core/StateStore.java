package shortener.core;

import java.io.IOException;

public interface StateStore {
    State load() throws IOException;

    void save(State state) throws IOException;
}
