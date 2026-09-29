package com.redsocial.feed;

import com.redsocial.post.Post;
import io.quarkus.websockets.next.CloseReason;
import io.quarkus.websockets.next.OpenConnections;
import io.quarkus.websockets.next.WebSocketConnection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Set;
import java.util.Map;

/**
 * Broadcast-only delivery of feed events over open /ws/feed connections.
 *
 * Mirrors ChatDelivery.publish: fan-out over open connections, per-connection
 * auth re-check, force-close on expired auth. Visibility is pruned ONCE per
 * event (one Neo4j query builds the eligible viewer set), then connections are
 * matched in memory — never N×M queries. The 1008 auth force-close is gated
 * behind that same visibility filter, so an invisible viewer is skipped.
 *
 * Wire envelopes are flat records so Jackson renders e.g.
 *   {"type":"like-changed","postId":"...","likeCount":1,"actorId":"...","liked":true}
 * and
 *   {"type":"comment-created","postId":"...","commentCount":1,"comment":{...}}
 */
@ApplicationScoped
public class FeedDelivery {
    private static final Logger LOG = Logger.getLogger(FeedDelivery.class);
    private static final CloseReason AUTH_EXPIRED = new CloseReason(1008, "Authentication expired");

    public record StatusEvent(String type, String error) {}
    public record CommentEvent(String type, String postId, long commentCount, Post.Comment comment) {}
    public record LikeEvent(String type, String postId, String actorId, long likeCount, boolean liked) {}
    public record CommentReactionEvent(String type, String postId, String commentId, String actorId,
                                       Map<String, Long> reactions, String emoji) {}

    @Inject OpenConnections connections;
    @Inject FeedTickets tickets;
    @Inject FeedRepository feed;

    public void publishCommentCreated(String postId, Post.Comment comment, long commentCount) {
        broadcast("comment-created", postId, new CommentEvent("comment-created", postId, commentCount, comment));
    }

    public void publishLikeChanged(String postId, String actorId, long likeCount, boolean liked) {
        broadcast("like-changed", postId, new LikeEvent("like-changed", postId, actorId, likeCount, liked));
    }

    public void publishCommentReactionChanged(String postId, String commentId, String actorId,
                                              Map<String, Long> reactions, String emoji) {
        broadcast("comment-reaction-changed", postId,
                new CommentReactionEvent("comment-reaction-changed", postId, commentId, actorId, reactions, emoji));
    }

    public void send(WebSocketConnection connection, Object event) {
        try {
            connection.sendTextAndAwait(event);
        } catch (RuntimeException ex) {
            LOG.warnf("Could not deliver feed event on connection %s: %s", connection.id(), ex.getMessage());
        }
    }

    private void broadcast(String type, String postId, Object event) {
        try {
            fanOut(postId, event);
        } catch (RuntimeException ex) {
            // Committed already: a delivery failure must not surface as a 500 that
            // invites a duplicate retry (addComment is not idempotent).
            LOG.warnf("Could not broadcast %s for post %s: %s", type, postId, ex.getClass().getSimpleName());
        }
    }

    private void fanOut(String postId, Object event) {
        Set<String> eligible = feed.visibleViewerIds(postId);
        for (WebSocketConnection connection : connections.listAll()) {
            FeedTickets.Viewer viewer = tickets.viewer(connection.id());
            if (viewer == null || !connection.isOpen()) continue;
            if (!eligible.contains(viewer.userId)) continue;
            if (!viewer.authValid()) {
                try {
                    connection.closeAndAwait(AUTH_EXPIRED);
                } catch (RuntimeException ex) {
                    LOG.warnf("Could not close expired feed connection %s: %s", connection.id(), ex.getMessage());
                }
                continue;
            }
            if (!viewer.wantsPost(postId)) continue;
            send(connection, event);
        }
    }
}
