package com.supportforge.agent;

public interface Agent {

    AgentType type();

    AgentResponse handle(AgentRequest request);
}