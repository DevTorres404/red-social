package com.redsocial.feed;

import com.redsocial.post.Post;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

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

    @Inject FeedService service;

    public record TicketResponse(String scope, String ticket) {}

    @POST
    @Path("/ws-ticket")
    @Operation(summary = "Issue a single-use ticket for the broadcast feed WebSocket channel")
    public TicketResponse ticket() {
        return new TicketResponse("feed", service.issueTicket());
    }

    @GET
    @Operation(summary = "Get home feed — posts from users you follow")
    public List<Post> getHomeFeed(
            @QueryParam("skip") @DefaultValue("0") int skip,
            @QueryParam("limit") @DefaultValue("20") int limit) {
        return service.getHomeFeed(skip, limit);
    }

    @GET
    @Path("/explore")
    @Operation(summary = "Get explore feed — posts from users you do not follow")
    public List<Post> getExploreFeed(
            @QueryParam("skip") @DefaultValue("0") int skip,
            @QueryParam("limit") @DefaultValue("20") int limit) {
        return service.getExploreFeed(skip, limit);
    }

}
