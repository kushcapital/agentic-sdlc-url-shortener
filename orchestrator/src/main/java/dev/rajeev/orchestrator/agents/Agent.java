package dev.rajeev.orchestrator.agents;

import dev.rajeev.orchestrator.core.Types.AgentKind;

/** A specialist that turns context into one validated artifact. */
public interface Agent<T> {

    AgentKind kind();

    Class<T> outputType();

    T produce(AgentContext ctx);
}
