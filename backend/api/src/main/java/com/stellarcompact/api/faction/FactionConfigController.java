package com.stellarcompact.api.faction;

import com.stellarcompact.api.match.MatchNotFoundException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;

/**
 * The Sovereign-config REST surface (board card E6-02; rest-api spec, section Sovereign
 * configuration). Thin controllers over the {@link FactionConfigService} seam:
 *
 * <ul>
 *   <li>{@code POST /api/games/{gameId}/factions} - attach a Sovereign config
 *       (persona/goals/hardConstraints/modelTier) to a free seat and bind the caller as
 *       its owner. Returns {@code 201 { factionId }}.</li>
 *   <li>{@code GET  /api/factions/{factionId}} - the seat's config; owner-only sensitive
 *       fields (persona/goals/constraints/tier) are <b>redacted</b> for non-owners.</li>
 *   <li>{@code PATCH /api/factions/{factionId}} - edit standing directives; owner-only and
 *       allowed only between matches (RUNNING maps to 409).</li>
 * </ul>
 *
 * <p><b>Owner resolution (the security crux).</b> The owning principal is determined
 * server-side, NEVER from a client-settable body field (principle 2). Resolution order:
 * <ol>
 *   <li>the authenticated request {@link Principal} ({@code Principal#getName()}), when a
 *       session/auth filter has set one; this is what the persistence/auth card will use;</li>
 *   <li>else the explicit {@code X-Owner-Token} header - the documented stand-in until
 *       session auth lands, so an owner can still demonstrate ownership in this drop;</li>
 *   <li>else {@code null} - an unauthenticated request, which <b>defaults to redaction</b>
 *       on read and is rejected on write.</li>
 * </ol>
 * The resolved name is the registry key, identical to the mechanism the WebSocket owner-view
 * gate (E6-04 {@code FactionOwnershipRegistry}) already uses, so a single binding governs
 * both the live per-faction stream and this config surface.
 *
 * <p><b>Not cacheable.</b> A config read is owner-sensitive and may change between matches,
 * so every response carries {@code Cache-Control: no-store} - a shared cache must never
 * serve one principal's owner view to another (defence in depth behind the redaction).
 *
 * <p>Blocking handlers on purpose: under Spring Boot 4 / Java 25 each request runs on its
 * own virtual thread.
 */
@RestController
public class FactionConfigController {

    /** Documented stand-in owner token until session auth lands (see class javadoc). */
    static final String OWNER_HEADER = "X-Owner-Token";

    private final FactionConfigService factions;

    public FactionConfigController(FactionConfigService factions) {
        this.factions = factions;
    }

    @PostMapping("/api/games/{gameId}/factions")
    public ResponseEntity<AttachFactionResponse> attach(
            @PathVariable("gameId") String gameId,
            @RequestBody(required = false) CreateFactionRequest request,
            @RequestHeader(name = OWNER_HEADER, required = false) String ownerToken,
            Principal principal) {

        String owner = resolveOwner(principal, ownerToken);
        AttachFactionResponse response = factions.attach(gameId, owner, request);
        return noStore(ResponseEntity.status(HttpStatus.CREATED)).body(response);
    }

    @GetMapping("/api/factions/{factionId}")
    public ResponseEntity<FactionConfigView> view(
            @PathVariable("factionId") String factionId,
            @RequestHeader(name = OWNER_HEADER, required = false) String ownerToken,
            Principal principal) {

        String requester = resolveOwner(principal, ownerToken);
        // A null/non-owner requester gets the redacted view (not an error) - the service
        // decides redaction from the authoritative registry, not from this argument alone.
        return noStore(ResponseEntity.ok()).body(factions.view(factionId, requester));
    }

    @PatchMapping("/api/factions/{factionId}")
    public ResponseEntity<FactionConfigView> patch(
            @PathVariable("factionId") String factionId,
            @RequestBody(required = false) UpdateDirectivesRequest request,
            @RequestHeader(name = OWNER_HEADER, required = false) String ownerToken,
            Principal principal) {

        String requester = resolveOwner(principal, ownerToken);
        return noStore(ResponseEntity.ok())
                .body(factions.updateDirectives(factionId, requester, request));
    }

    /** Authenticated principal wins; else the documented header; else null (unauthenticated). */
    private static String resolveOwner(Principal principal, String ownerToken) {
        if (principal != null && principal.getName() != null && !principal.getName().isBlank()) {
            return principal.getName();
        }
        if (ownerToken != null && !ownerToken.isBlank()) {
            return ownerToken;
        }
        return null;
    }

    // ===== error mapping =======================================================

    /** Unknown match maps to 404 (raised by the match seam during attach). */
    @ExceptionHandler(MatchNotFoundException.class)
    public ResponseEntity<String> matchNotFound(MatchNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
    }

    /** Unknown faction handle maps to 404. */
    @ExceptionHandler(FactionNotFoundException.class)
    public ResponseEntity<String> factionNotFound(FactionNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
    }

    /** Non-owner (or unauthenticated) write maps to 403 Forbidden. */
    @ExceptionHandler(NotFactionOwnerException.class)
    public ResponseEntity<String> notOwner(NotFactionOwnerException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(e.getMessage());
    }

    /** Directive edit while the match is RUNNING maps to 409 Conflict (between-matches gate). */
    @ExceptionHandler(DirectivesLockedException.class)
    public ResponseEntity<String> locked(DirectivesLockedException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
    }

    /** Malformed input maps to 400. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> badRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
    }

    private static ResponseEntity.BodyBuilder noStore(ResponseEntity.BodyBuilder builder) {
        return builder.cacheControl(CacheControl.noStore());
    }
}
