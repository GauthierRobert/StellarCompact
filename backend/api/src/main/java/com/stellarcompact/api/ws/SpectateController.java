package com.stellarcompact.api.ws;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Controller;

/**
 * Handles the optional spectate hint a client SENDs to
 * {@code /app/games/{gameId}/spectate { minX,minY,maxX,maxY }}
 * (board card E6-04; skill rule 4 - scope overlay deltas to the spectator camera bbox to
 * bound payload at scale). The bbox is remembered on the session so future overlay diffs
 * can be tailored; a malformed bbox is rejected and ignored (it never widens what the
 * client sees - public state only).
 *
 * <p>This is public spectate input: it carries no per-faction state and grants no
 * private access. The owner-only WorldView is delivered separately and is gated by
 * {@link OwnerViewAuthorizationInterceptor}.
 */
@Controller
public class SpectateController {

    private static final Logger LOG = LoggerFactory.getLogger(SpectateController.class);

    /** Session attribute under which the spectator bbox is remembered. */
    static final String BBOX_ATTR = "spectateBbox";

    @MessageMapping("/games/{gameId}/spectate")
    public void spectate(@DestinationVariable String gameId,
                         LiveMessages.SpectateRequest request,
                         SimpMessageHeaderAccessor headers) {
        if (request == null) {
            return;
        }
        var error = request.validationError();
        if (error.isPresent()) {
            LOG.debug("ignoring malformed spectate bbox for game {}: {}", gameId, error.get());
            return;
        }
        var attrs = headers.getSessionAttributes();
        if (attrs != null) {
            attrs.put(BBOX_ATTR, request);
        }
    }
}
