package com.stellarcompact.api.auth;

import com.stellarcompact.api.security.JwtProperties;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Mints the dev username-only JWT (board card "OAuth/security — dev JWT"; spec
 * {@code docs/specs/rest-api.md} "Authentication (dev)"). HS256, claims {@code sub} =
 * username, {@code iss} = configured issuer, {@code iat}/{@code exp} from the configured TTL.
 *
 * <p>Wall-clock is fine here: this is API-layer identity, not the deterministic engine
 * (principle 1 governs the engine module only). When the project moves to Google OAuth, this
 * service is removed — Google issues the token; only the {@link org.springframework.security.oauth2.jwt.JwtDecoder}
 * stays (pointed at Google's JWKS).
 */
@Service
public class DevTokenService {

    private final JwtEncoder encoder;
    private final JwtProperties props;

    public DevTokenService(JwtEncoder encoder, JwtProperties props) {
        this.encoder = encoder;
        this.props = props;
    }

    /** Mint a token for {@code username} (assumed already validated by the controller). */
    public LoginResponse mint(String username) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(props.getTtl());

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(props.getIssuer())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .subject(username)
                .build();

        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();

        return new LoginResponse(token, username, expiresAt.toString(), "Bearer");
    }
}
