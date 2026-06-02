package com.stellarcompact.api.galaxy;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the fallback {@link ActiveSystemIndex} bean - "nothing promoted yet",
 * so fine-level star tiles report every star as pure scenery
 * ({@code activeSystemId == null}) until promotion wiring (E2-05/E6-01) supplies a
 * real index. Marked {@link ConditionalOnMissingBean} so that real implementation,
 * when it lands, transparently takes over without touching this card's code.
 */
@Configuration
public class DefaultActiveSystemIndex {

    @Bean
    @ConditionalOnMissingBean(ActiveSystemIndex.class)
    public ActiveSystemIndex noActiveSystems() {
        return new ActiveSystemIndex.NoActiveSystems();
    }
}
