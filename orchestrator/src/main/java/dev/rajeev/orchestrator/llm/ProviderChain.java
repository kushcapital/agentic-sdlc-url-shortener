package dev.rajeev.orchestrator.llm;

import java.util.List;
import java.util.function.Consumer;

/**
 * Fallback chain: try providers in order; on a provider failure move to the next and report the
 * fallback so it lands in the audit log. A schema-invalid output is NOT a provider failure — that is
 * the agent layer's retry to make.
 */
public final class ProviderChain implements LlmProvider {

    public record Fallback(String from, String to, String reason) {}

    private final List<LlmProvider> providers;
    private final Consumer<Fallback> onFallback;

    public ProviderChain(List<LlmProvider> providers, Consumer<Fallback> onFallback) {
        this.providers = List.copyOf(providers);
        this.onFallback = onFallback;
    }

    @Override
    public String name() {
        return String.join(">", providers.stream().map(LlmProvider::name).toList());
    }

    @Override
    public boolean available() {
        return providers.stream().anyMatch(LlmProvider::available);
    }

    @Override
    public Response complete(Request req) {
        List<LlmProvider> usable = providers.stream().filter(LlmProvider::available).toList();
        if (usable.isEmpty()) throw new ProviderException("no LLM provider is available", name(), false);
        RuntimeException last = null;
        for (int i = 0; i < usable.size(); i++) {
            LlmProvider p = usable.get(i);
            try {
                return p.complete(req);
            } catch (ProviderException e) {
                last = e;
                if (i + 1 < usable.size() && onFallback != null) onFallback.accept(new Fallback(p.name(), usable.get(i + 1).name(), e.getMessage()));
            }
        }
        throw last;
    }
}
