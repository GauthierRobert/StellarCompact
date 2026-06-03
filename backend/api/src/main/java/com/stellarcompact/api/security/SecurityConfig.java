package com.stellarcompact.api.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The dev authentication wiring (board card "OAuth/security — dev JWT"; spec
 * {@code docs/specs/rest-api.md} "Authentication (dev)").
 *
 * <p>It does three things and nothing the existing trust model does not already assume:
 * <ol>
 *   <li>Mints + verifies an <b>HS256</b> JWT whose {@code sub} is the username. The resource
 *       server populates the request {@link java.security.Principal} from {@code sub}, so the
 *       <em>existing</em> owner resolution (Principal wins over {@code X-Owner-Token}) makes a
 *       logged-in user the owner of any seat they attach — no controller changes.</li>
 *   <li>Leaves all public spectator surfaces open ({@code GET /api/games/**}, tiles,
 *       {@code /ws/**}, {@code POST /api/auth/login}); requires a token only for
 *       {@code /api/me/**} and {@code GET /api/auth/me}. Config writes keep the documented
 *       {@code X-Owner-Token} dev stand-in working alongside JWT.</li>
 *   <li>Exposes the {@link JwtDecoder} so the WebSocket handshake can verify
 *       {@code ?access_token=} and pin a <em>verified</em> principal (closing the prior
 *       {@code ?principal=} spoof hole).</li>
 * </ol>
 *
 * <p><b>Google-swap seam.</b> Moving to Google OAuth replaces only the {@link #jwtDecoder}
 * (point it at Google's JWKS / issuer) and the token mint (Google issues it, so
 * {@link com.stellarcompact.api.auth.DevTokenService} and {@link #jwtEncoder} go away). The
 * filter chain, the controllers and the {@code FactionOwnershipRegistry} trust decision are
 * untouched.
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(JwtProperties.class)
public class SecurityConfig {

    private final JwtProperties props;

    public SecurityConfig(JwtProperties props) {
        this.props = props;
        byte[] bytes = props.getSecret().getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException(
                    "stellar-compact.auth.jwt.secret must be at least 32 bytes for HS256 "
                            + "(got " + bytes.length + ")");
        }
    }

    /** The HS256 signing/verifying key derived from the configured secret. */
    @Bean
    SecretKey jwtSecretKey() {
        return new SecretKeySpec(props.getSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    /** Verifies HS256 signature, {@code exp}/{@code nbf}, and the configured {@code iss}. */
    @Bean
    JwtDecoder jwtDecoder(SecretKey key) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(props.getIssuer()));
        return decoder;
    }

    /** Mints the dev token (used only by {@link com.stellarcompact.api.auth.DevTokenService}). */
    @Bean
    JwtEncoder jwtEncoder(SecretKey key) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(key));
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtDecoder jwtDecoder) throws Exception {
        http
                // Stateless Bearer auth: no CSRF token, no server session.
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Login is open (no token yet); public spectator reads + WS handshake open.
                        .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                        .requestMatchers("/ws/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/games/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/galaxy/**").permitAll()
                        // Personal surfaces require a verified token.
                        .requestMatchers("/api/me/**").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/auth/me").authenticated()
                        // Everything else (config writes etc.) stays open at the filter level:
                        // owner is resolved server-side from Principal → X-Owner-Token → null.
                        .anyRequest().permitAll())
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.decoder(jwtDecoder)));
        return http.build();
    }

    /**
     * Permissive dev CORS so an Angular dev server on another origin can call the API with a
     * Bearer token. Credentials are not cookie-based (token in the {@code Authorization}
     * header), so wildcard origins are safe here. Tighten for production.
     */
    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedOriginPatterns(List.of("*"));
        cfg.setAllowedMethods(List.of("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS"));
        cfg.setAllowedHeaders(List.of("*"));
        cfg.setExposedHeaders(List.of("ETag"));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cfg);
        return source;
    }
}
