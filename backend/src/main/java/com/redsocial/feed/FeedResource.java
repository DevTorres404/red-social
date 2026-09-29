package com.redsocial.feed;

import com.redsocial.common.CurrentUser;
import com.redsocial.post.Post;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.time.Instant;
import java.util.List;

/**
 * Feed REST endpoints.
 *
 * GET /api/feed        — Home feed: posts from followed users
 * GET /api/feed/explore — Explore: posts from non-followed users
 * POST /api/feed/ws-ticket — Single-use ticket for the /ws/feed channel
 */
@Path("/api/feed")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("user")
@Tag(name = "Feed")
public class FeedResource {

    @Inject
    FeedRepository feedRepository;

    @Inject
    FeedTickets tickets;

    @Inject
    CurrentUser currentUser;

    @Inject
    JsonWebToken jwt;

    public record TicketResponse(String scope, String ticket) {}

    @POST
    @Path("/ws-ticket")
    @Operation(summary = "Issue a single-use ticket for the broadcast feed WebSocket channel")
    public TicketResponse ticket() {
        return tickets.issue(currentUser.id(), Instant.ofEpochSecond(jwt.getExpirationTime()));
    }

    @GET
    @Operation(summary = "Get home feed — posts from users you follow")
    public List<Post> getHomeFeed(
            @QueryParam("skip") @DefaultValue("0") int skip,
            @QueryParam("limit") @DefaultValue("20") int limit) {
        validatePage(skip, limit);
        return feedRepository.getHomeFeed(currentUser.id(), skip, limit);
    }

    @GET
    @Path("/explore")
    @Operation(summary = "Get explore feed — posts from users you do not follow")
    public List<Post> getExploreFeed(
            @QueryParam("skip") @DefaultValue("0") int skip,
            @QueryParam("limit") @DefaultValue("20") int limit) {
        validatePage(skip, limit);
        return feedRepository.getExploreFeed(currentUser.id(), skip, limit);
    }

    static void validatePage(int skip, int limit) {
        if (skip < 0 || skip > 10_000 || limit < 1 || limit > 100) {
            throw new BadRequestException("skip must be 0..10000 and limit must be 1..100");
        }
    }
}
