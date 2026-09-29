package com.redsocial.notification;

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

    @Inject
    NotificationRepository notificationRepository;

    @Inject
    CurrentUser currentUser;

    @GET
    @Operation(summary = "Get all notifications for the current user")
    public List<Notification> getAll() {
        String userId = currentUser.id();
        return notificationRepository.findByUser(userId);
    }

    @GET
    @Path("/unread")
    @Operation(summary = "Get the count of unread notifications")
    public Response getUnreadCount() {
        String userId = currentUser.id();
        long count = notificationRepository.countUnread(userId);
        return Response.ok(Map.of("unread", count)).build();
    }

    @POST
    @Path("/read-all")
    @Operation(summary = "Mark all notifications as read")
    public Response markAllAsRead() {
        String userId = currentUser.id();
        notificationRepository.markAllAsRead(userId);
        return Response.noContent().build();
    }
}
