package com.redsocial.messaging;

import com.redsocial.messaging.dto.SendMessageRequest;
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

    @Inject MessageService service;

    @POST
    @Operation(summary = "Send a direct message to another user")
    public Response send(@Valid SendMessageRequest request) {
        return Response.status(Response.Status.CREATED).entity(service.send(request)).build();
    }

    @GET
    @Path("/conversations")
    @Operation(summary = "List IDs of all users you have conversations with")
    public List<String> getConversationPartners() {
        return service.getConversationPartners();
    }

    @GET
    @Path("/{userId}")
    @Operation(summary = "Get a paginated conversation thread with a specific user")
    public List<Message> getConversation(@PathParam("userId") String otherUserId,
                                         @QueryParam("skip") @DefaultValue("0") int skip,
                                         @QueryParam("limit") @DefaultValue("50") int limit) {
        return service.getConversation(otherUserId, skip, limit);
    }

    public record TicketRequest(String otherUserId) {}
    public record TicketResponse(String conversationId, String ticket) {}

    @POST
    @Path("/ws-ticket")
    @Operation(summary = "Issue a single-use, conversation-bound WebSocket ticket")
    public TicketResponse ticket(TicketRequest request) {
        var issued = service.ticket(request == null ? null : request.otherUserId());
        return new TicketResponse(issued.conversationId(), issued.ticket());
    }

    @POST
    @Path("/{userId}/read")
    @Operation(summary = "Mark all messages from a user as read")
    public Response markAsRead(@PathParam("userId") String senderId) {
        service.markAsRead(senderId);
        return Response.noContent().build();
    }
}
