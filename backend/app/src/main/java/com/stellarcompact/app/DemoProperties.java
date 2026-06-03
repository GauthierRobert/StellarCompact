package com.stellarcompact.app;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.stereotype.Component;

/**
 * Externalized configuration for the demo-match autostart (E11-07).
 *
 * <p>All properties are optional with sensible defaults. The autostart bean itself is
 * {@code @ConditionalOnProperty(stellar-compact.demo.autostart=true)} so these
 * properties are only evaluated when the feature is opted in. They are kept in a
 * separate class so the main {@code application.yml} stays uncluttered.
 *
 * <p>Example {@code application-demo.yml} override:
 * <pre>
 * stellar-compact:
 *   demo:
 *     autostart: true
 *     faction-count: 4
 *     balance-profile: large-persistent
 *     seed: 42
 * </pre>
 */
@Component
@ConfigurationProperties(prefix = "stellar-compact.demo")
public class DemoProperties {

    /** Number of AI factions in the demo match (clamped 2-8 by the service). */
    private int factionCount = 2;

    /** Balance profile name (must match a /balance/*.json resource). */
    private String balanceProfile = "small-default";

    /**
     * Optional fixed seed. {@code null} means the service derives one deterministically
     * from its internal counter (principle 1 — never wall-clock). Keeping this null in
     * the default config produces a fresh galaxy each boot.
     */
    private Long seed = null;

    public int factionCount() { return factionCount; }
    public void setFactionCount(int factionCount) { this.factionCount = factionCount; }

    public String balanceProfile() { return balanceProfile; }
    public void setBalanceProfile(String balanceProfile) { this.balanceProfile = balanceProfile; }

    public Long seed() { return seed; }
    public void setSeed(Long seed) { this.seed = seed; }
}
