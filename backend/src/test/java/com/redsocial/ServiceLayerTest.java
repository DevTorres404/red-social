package com.redsocial;

import com.redsocial.auth.AuthResource;
import com.redsocial.feed.FeedResource;
import com.redsocial.graph.GraphResource;
import com.redsocial.messaging.MessageResource;
import com.redsocial.messaging.PresenceResource;
import com.redsocial.notification.NotificationResource;
import com.redsocial.notification.PushResource;
import com.redsocial.post.PostResource;
import com.redsocial.user.UserResource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServiceLayerTest {
    @Test
    void restResourcesDependOnlyOnApplicationServices() {
        for (Class<?> resource : List.of(AuthResource.class, FeedResource.class, GraphResource.class,
                MessageResource.class, PresenceResource.class, NotificationResource.class,
                PushResource.class, PostResource.class, UserResource.class)) {
            var dependencies = List.of(resource.getDeclaredFields()).stream()
                    .filter(field -> field.isAnnotationPresent(Inject.class)).toList();
            assertEquals(1, dependencies.size(), resource.getSimpleName());
            Class<?> service = dependencies.get(0).getType();
            assertTrue(service.getSimpleName().endsWith("Service"), resource.getSimpleName());
            assertTrue(service.isAnnotationPresent(ApplicationScoped.class), service.getSimpleName());
        }
    }
}
