package com.redsocial.messaging;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;

/**
 * Presence REST endpoints.
 * Allows querying online status of users.
 */
@Path("/api/presence")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("user")
@Tag(name = "Presence")
public class PresenceResource {

    public record SessionRequest(String sessionId) {}

    @Inject
    PresenceService presence;


    @POST
    @Path("/session")
    @Operation(summary = "Keep the authenticated user's app session online")
    public Response heartbeat(SessionRequest request) {
        presence.registerAppSession(request == null ? null : request.sessionId());
        return Response.noContent().build();
    }

    @DELETE
    @Path("/session/{sessionId}")
    @Operation(summary = "Mark the authenticated user's app session offline")
    public Response disconnect(@PathParam("sessionId") String value) {
        presence.disconnectAppSession(value);
        return Response.noContent().build();
    }

    @GET
    @Path("/status/{userId}")
    @Operation(summary = "Get online status of a specific user")
    public Response getStatus(@PathParam("userId") String userId) {
        return Response.ok(presence.getStatus(userId)).build();
    }

    @POST
    @Path("/status/batch")
    @Operation(summary = "Get online status for multiple users")
    public Response getBatchStatus(List<String> userIds) {
        return Response.ok(presence.getBatchStatus(userIds)).build();
    }

    @GET
    @Path("/online")
    @Operation(summary = "Get all online user IDs")
    public Response getOnlineUsers() {
        return Response.ok(presence.getOnlineUserIds()).build();
    }

    @GET
    @Path("/me")
    @Operation(summary = "Get current user's own presence info")
    public Response getMyStatus() {
        return Response.ok(presence.getMyStatus()).build();
    }
}
