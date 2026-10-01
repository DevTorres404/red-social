package com.redsocial.messaging;

import com.redsocial.common.CurrentUser;
import com.redsocial.messaging.dto.SendMessageRequest;
import com.redsocial.notification.InAppNotificationService;
import com.redsocial.notification.PushNotificationService;
import com.redsocial.user.UserRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotFoundException;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.time.Instant;
import java.util.List;

@ApplicationScoped
public class MessageService {
    public record Ticket(String conversationId, String ticket) {}
    @Inject MessageRepository messages;
    @Inject CurrentUser currentUser;
    @Inject UserRepository users;
    @Inject ChatDelivery delivery;
    @Inject ChatTickets tickets;
    @Inject JsonWebToken jwt;
    @Inject PushNotificationService pushNotifications;
    @Inject InAppNotificationService inAppNotifications;

    public Message send(SendMessageRequest request) {
        String senderId = currentUser.id();
        requireMessagingPermission(senderId, request.recipientId());
        Message message = messages.send(senderId, request.recipientId(), request.text());
        delivery.publish(message, null);
        inAppNotifications.onMessageSent(senderId, request.recipientId(), message.id());
        pushNotifications.onMessageSent(senderId, request.recipientId(), message.id(), message.conversacionId());
        return message;
    }

    public List<String> getConversationPartners() {
        return messages.getConversationPartners(currentUser.id());
    }

    public List<Message> getConversation(String otherUserId, int skip, int limit) {
        String myId = currentUser.id();
        if (skip < 0 || skip > 10_000 || limit < 1 || limit > 100)
            throw new BadRequestException("skip must be 0..10000 and limit 1..100");
        requireOtherUser(myId, otherUserId);
        return messages.getConversation(myId, otherUserId, skip, limit);
    }

    public Ticket ticket(String otherUserId) {
        if (otherUserId == null) throw new BadRequestException("Other user is required");
        String myId = currentUser.id();
        requireOtherUser(myId, otherUserId);
        requireMessagingPermission(myId, otherUserId);
        var issued = tickets.issue(myId, otherUserId, Instant.ofEpochSecond(jwt.getExpirationTime()));
        return new Ticket(issued.conversationId(), issued.ticket());
    }

    private void requireMessagingPermission(String senderId, String recipientId) {
        var recipient = users.findById(recipientId).orElseThrow(() -> new NotFoundException("User not found"));
        if (recipient.messagesFollowersOnly() && !senderId.equals(recipientId)
                && !users.isFollowing(senderId, recipientId))
            throw new ForbiddenException("Solo los seguidores pueden enviar mensajes");
    }

    private void requireOtherUser(String myId, String otherUserId) {
        if (otherUserId == null || otherUserId.isBlank() || otherUserId.equals(myId))
            throw new BadRequestException("Choose another user");
        users.findById(otherUserId).orElseThrow(() -> new NotFoundException("User not found: " + otherUserId));
    }

    public void markAsRead(String senderId) {
        messages.markAsRead(currentUser.id(), senderId);
    }
}
