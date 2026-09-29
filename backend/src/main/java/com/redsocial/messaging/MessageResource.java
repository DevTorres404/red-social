package com.redsocial.messaging;

import com.redsocial.common.CurrentUser;
import com.redsocial.messaging.dto.SendMessageRequest;
import com.redsocial.notification.InAppNotificationService;
import com.redsocial.notification.PushNotificationService;
import com.redsocial.user.UserRepository;
import org.eclipse.microprofile.jwt.JsonWebToken;
import java.time.Instant;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;

/**
 * REST endpoints for direct messaging.
 *
 * POST /api/messages                         — Send a message
 * GET  /api/messages/{userId}                — Get conversation thread with a user
 * POST /api/messages/{userId}/read           — Mark messages from a user as read
 * GET  /api/messages/conversations           — List all conversation partners
 */
@Path("/api/messages")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("user")
@Tag(name = "Messaging")
public class MessageResource {

    @Inject
    MessageRepository messageRepository;

    @Inject
    CurrentUser currentUser;

    @Inject
    UserRepository users;

    @Inject
    ChatDelivery delivery;

    @Inject
    ChatTickets tickets;

    @Inject
    JsonWebToken jwt;

    @Inject
    PushNotificationService pushNotifications;

    @Inject
    InAppNotificationService inAppNotifications;

    @POST
    @Operation(summary = "Send a direct message to another user")
    public Response send(@Valid SendMessageRequest request) {
        String senderId = currentUser.id();
        Message message = messageRepository.send(senderId, request.recipientId(), request.text());
        delivery.publish(message, null);
        inAppNotifications.onMessageSent(senderId, request.recipientId(), message.id());
        pushNotifications.onMessageSent(senderId, request.recipientId(), message.id(), message.conversacionId());
        return Response.status(Response.Status.CREATED).entity(message).build();
    }

    @GET
    @Path("/conversations")
    @Operation(summary = "List IDs of all users you have conversations with")
    public List<String> getConversationPartners() {
        String userId = currentUser.id();
        return messageRepository.getConversationPartners(userId);
    }

    @GET
    @Path("/{userId}")
    @Operation(summary = "Get a paginated conversation thread with a specific user")
    public List<Message> getConversation(@PathParam("userId") String otherUserId,
                                         @QueryParam("skip") @DefaultValue("0") int skip,
                                         @QueryParam("limit") @DefaultValue("50") int limit) {
        String myId = currentUser.id();
        if (skip < 0 || skip > 10_000 || limit < 1 || limit > 100) {
            throw new BadRequestException("skip must be 0..10000 and limit 1..100");
        }
        requireOtherUser(myId, otherUserId);
        return messageRepository.getConversation(myId, otherUserId, skip, limit);
    }

    public record TicketRequest(String otherUserId) {}
    public record TicketResponse(String conversationId, String ticket) {}

    @POST
    @Path("/ws-ticket")
    @Operation(summary = "Issue a single-use, conversation-bound WebSocket ticket")
    public TicketResponse ticket(TicketRequest request) {
        String myId = currentUser.id();
        if (request == null) throw new BadRequestException("Other user is required");
        requireOtherUser(myId, request.otherUserId());
        return tickets.issue(myId, request.otherUserId(), Instant.ofEpochSecond(jwt.getExpirationTime()));
    }

    private void requireOtherUser(String myId, String otherUserId) {
        if (otherUserId == null || otherUserId.isBlank() || otherUserId.equals(myId)) {
            throw new BadRequestException("Choose another user");
        }
        users.findById(otherUserId)
                .orElseThrow(() -> new NotFoundException("User not found: " + otherUserId));
    }

    @POST
    @Path("/{userId}/read")
    @Operation(summary = "Mark all messages from a user as read")
    public Response markAsRead(@PathParam("userId") String senderId) {
        String myId = currentUser.id();
        messageRepository.markAsRead(myId, senderId);
        return Response.noContent().build();
    }
}
