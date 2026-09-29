package com.redsocial.notification;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InAppNotificationServiceTest {
    static class RecordingRepository extends NotificationRepository {
        final List<String> events = new ArrayList<>();

        @Override
        public Notification createOnce(String id, String target, String type, String actor, String postId) {
            events.add(id + "|" + target + "|" + type + "|" + actor + "|" + postId);
            return null;
        }

        @Override
        public void createForFollowers(String authorId, String postId) {
            events.add("post:" + authorId + ":" + postId);
        }
    }

    @Test
    void routesEventsToTheRightRecipientWithStableIds() {
        var repository = new RecordingRepository();
        var service = new InAppNotificationService();
        service.notifications = repository;

        service.onPostCreated("author", "post");
        service.onPostLiked("actor", "post", "author");
        service.onPostCommented("actor", "post", "comment", "author");
        service.onUserFollowed("actor", "author");
        service.onMessageSent("actor", "author", "message");

        assertEquals(List.of(
                "post:author:post",
                "like:actor:post|author|LIKE|actor|post",
                "comment:comment|author|COMMENT|actor|post",
                "follow:actor:author|author|FOLLOW|actor|null",
                "message:message|author|MESSAGE|actor|null"
        ), repository.events);
    }

    @Test
    void skipsSelfNotifications() {
        var repository = new RecordingRepository();
        var service = new InAppNotificationService();
        service.notifications = repository;

        service.onPostLiked("same", "post", "same");
        service.onPostCommented("same", "post", "comment", "same");
        service.onUserFollowed("same", "same");
        service.onMessageSent("same", "same", "message");

        assertEquals(List.of(), repository.events);
    }
}
