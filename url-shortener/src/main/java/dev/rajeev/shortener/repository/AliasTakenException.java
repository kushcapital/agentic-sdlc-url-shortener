package dev.rajeev.shortener.repository;

public class AliasTakenException extends RuntimeException {

    private static final long serialVersionUID = 1L;
    private final String code;

    public AliasTakenException(String code) {
        super("short code '" + code + "' is already in use");
        this.code = code;
    }

    public String code() {
        return code;
    }
}
