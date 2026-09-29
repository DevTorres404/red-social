package com.redsocial.notification;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/** Persistent Orbit inbox. Web Push is only an optional second delivery channel. */
@ApplicationScoped
public class InAppNotificationService {
    private static final Logger LOG = Logger.getLogger(InAppNotificationService.class);

    @Inject NotificationRepository notifications;

    public void onPostCreated(String authorId, String postId) {
        record("POST", () -> notifications.createForFollowers(authorId, postId));
    }

    public void onPostLiked(String actorId, String postId, String authorId) {
        if (actorId.equals(authorId)) return;
        record("LIKE", () -> notifications.createOnce("like:" + actorId + ":" + postId,
                authorId, "LIKE", actorId, postId));
    }

    public void onPostCommented(String actorId, String postId, String commentId, String authorId) {
        if (actorId.equals(authorId)) return;
        record("COMMENT", () -> notifications.createOnce("comment:" + commentId,
                authorId, "COMMENT", actorId, postId));
    }

    public void onUserFollowed(String actorId, String followedId) {
        if (actorId.equals(followedId)) return;
        record("FOLLOW", () -> notifications.createOnce("follow:" + actorId + ":" + followedId,
                followedId, "FOLLOW", actorId, null));
    }

    public void onMessageSent(String senderId, String recipientId, String messageId) {
        if (senderId.equals(recipientId)) return;
        record("MESSAGE", () -> notifications.createOnce("message:" + messageId,
                recipientId, "MESSAGE", senderId, null));
    }

    private void record(String type, Runnable write) {
        try {
            write.run();
        } catch (RuntimeException ex) {
            // The action was already persisted. Do not return a 500 that encourages
            // the user to repeat a post/message merely because its alert failed.
            LOG.warnf("Could not save %s in-app notification: %s", type, ex.getClass().getSimpleName());
        }
    }
}
