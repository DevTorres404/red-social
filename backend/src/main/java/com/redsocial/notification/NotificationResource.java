package com.redsocial.notification;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;
import java.util.Map;

/**
 * REST endpoints for notifications.
 *
 * GET  /api/notifications           — Get all notifications for the current user
 * GET  /api/notifications/unread    — Count of unread notifications
 * POST /api/notifications/read-all  — Mark all as read
 */
@Path("/api/notifications")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("user")
@Tag(name = "Notifications")
public class NotificationResource {

    @Inject NotificationService service;

    @GET
    @Operation(summary = "Get all notifications for the current user")
    public List<Notification> getAll() {
        return service.getAll();
    }

    @GET
    @Path("/unread")
    @Operation(summary = "Get the count of unread notifications")
    public Response getUnreadCount() {
        return Response.ok(Map.of("unread", service.getUnreadCount())).build();
    }

    @POST
    @Path("/read-all")
    @Operation(summary = "Mark all notifications as read")
    public Response markAllAsRead() {
        service.markAllAsRead();
        return Response.noContent().build();
    }
}
