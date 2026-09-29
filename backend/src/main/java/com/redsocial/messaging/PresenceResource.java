package com.redsocial.messaging;

import com.redsocial.common.CurrentUser;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;
import java.util.Map;
import java.util.UUID;

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

    @Inject
    CurrentUser currentUser;

    @POST
    @Path("/session")
    @Operation(summary = "Keep the authenticated user's app session online")
    public Response heartbeat(SessionRequest request) {
        String sessionId = validSessionId(request == null ? null : request.sessionId());
        presence.registerConnection(currentUser.id(), "app:" + sessionId);
        return Response.noContent().build();
    }

    @DELETE
    @Path("/session/{sessionId}")
    @Operation(summary = "Mark the authenticated user's app session offline")
    public Response disconnect(@PathParam("sessionId") String value) {
        presence.unregisterConnection(currentUser.id(), "app:" + validSessionId(value));
        return Response.noContent().build();
    }

    static String validSessionId(String value) {
        if (value == null) throw new BadRequestException("Invalid session ID");
        try {
            UUID id = UUID.fromString(value);
            if (!id.toString().equals(value)) throw new IllegalArgumentException();
            return value;
        } catch (IllegalArgumentException error) {
            throw new BadRequestException("Invalid session ID");
        }
    }

    @GET
    @Path("/status/{userId}")
    @Operation(summary = "Get online status of a specific user")
    public Response getStatus(@PathParam("userId") String userId) {
        // Users can only see status of users they follow or are in conversation with
        // For now, allow querying any user (can be restricted later)
        boolean online = presence.isOnline(userId);
        int connections = presence.getConnectionCount(userId);
        return Response.ok(Map.of(
                "userId", userId,
                "online", online,
                "connectionCount", connections
        )).build();
    }

    @POST
    @Path("/status/batch")
    @Operation(summary = "Get online status for multiple users")
    public Response getBatchStatus(List<String> userIds) {
        // Filter out current user
        List<String> filtered = userIds.stream()
                .filter(id -> !id.equals(currentUser.id()))
                .distinct()
                .toList();

        Map<String, Boolean> statuses = presence.getOnlineStatus(filtered);
        return Response.ok(statuses).build();
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
        boolean online = presence.isOnline(currentUser.id());
        int connections = presence.getConnectionCount(currentUser.id());
        return Response.ok(Map.of(
                "userId", currentUser.id(),
                "online", online,
                "connectionCount", connections
        )).build();
    }
}
