package com.stellarcompact.agentruntime.chat;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the agent-runtime model-selection configuration surface (card E4-01).
 *
 * <p>This class deliberately declares <em>no</em> provider, model, or endpoint. It only
 * enables {@link AgentRuntimeProperties} binding; the actual {@code ChatModel}/{@code
 * ChatClient.Builder} beans come from Spring AI auto-configuration for whichever model
 * starter is on the classpath and active (Ollama by default — see this module's pom and
 * {@code application-agent.yml}; OpenAI/others under their profile). The {@link
 * SovereignChatClientFactory} component is picked up by component scanning under the
 * {@code com.stellarcompact} base package configured by the app entry point.
 *
 * <p>Keeping provider choice in starters + {@code spring.ai.*} properties and tier→model
 * mapping in {@link AgentRuntimeProperties} is what makes provider/model switching
 * config-only (principle 4).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AgentRuntimeProperties.class)
public class AgentRuntimeChatConfiguration {
}
