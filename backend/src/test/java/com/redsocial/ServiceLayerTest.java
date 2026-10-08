package com.redsocial;

import com.redsocial.auth.AuthResource;
import com.redsocial.auth.LoginRateLimiter;
import com.redsocial.feed.FeedResource;
import com.redsocial.graph.GraphResource;
import com.redsocial.messaging.MessageResource;
import com.redsocial.messaging.PresenceResource;
import com.redsocial.notification.NotificationResource;
import com.redsocial.notification.PushResource;
import com.redsocial.post.PostResource;
import com.redsocial.user.UserResource;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServiceLayerTest {
    @Test
    void restResourcesDependOnlyOnApplicationServices() {
        for (Class<?> resource : List.of(AuthResource.class, FeedResource.class, GraphResource.class,
                MessageResource.class, PresenceResource.class, NotificationResource.class,
                PushResource.class, PostResource.class, UserResource.class)) {
            var dependencies = List.of(resource.getDeclaredFields()).stream()
                    .filter(field -> field.isAnnotationPresent(Inject.class)).toList();
            assertFalse(dependencies.isEmpty(), resource.getSimpleName());
            for (var dependency : dependencies) {
                Class<?> type = dependency.getType();
                // La capa de recursos sólo inyecta colaboradores de aplicación:
                // services o el LoginRateLimiter (bean @ApplicationScoped vía
                // LoginRateLimiterProducer). Nunca repositorios ni infraestructura.
                boolean isService = type.getSimpleName().endsWith("Service");
                boolean isRateLimiter = type == LoginRateLimiter.class;
                assertTrue(isService || isRateLimiter, type.getSimpleName());
            }
        }
    }
}