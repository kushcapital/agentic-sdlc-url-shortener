package dev.rajeev.orchestrator.core;

public class OrchestrationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public enum Kind { GATE, AGENT, POLICY, TOOL, CONFIG, STOPPED, TIMEOUT, BUDGET }

    private final Kind kind;
    private final boolean retryable;

    public OrchestrationException(String message, Kind kind, boolean retryable) {
        super(message);
        this.kind = kind;
        this.retryable = retryable;
    }

    public OrchestrationException(String message, Kind kind, boolean retryable, Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.retryable = retryable;
    }

    public Kind kind() { return kind; }

    public boolean retryable() { return retryable; }
}
