package dev.rajeev.shortener.domain;

public class CodeExhaustedException extends RuntimeException {

    private static final long serialVersionUID = 1L;
    public CodeExhaustedException(int attempts) {
        super("could not find a free short code after " + attempts + " attempts");
    }
}
