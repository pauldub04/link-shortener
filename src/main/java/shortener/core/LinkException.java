package shortener.core;

public class LinkException extends Exception {
    private static final long serialVersionUID = 1L;

    public enum Reason {
        INVALID_URL,
        INVALID_LIMIT,
        INVALID_EDIT,
        UNKNOWN_USER,
        NOT_FOUND,
        ACCESS_DENIED,
        EXPIRED,
        EXHAUSTED,
        CODE_SPACE_EXHAUSTED
    }

    private final Reason reason;

    public LinkException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
