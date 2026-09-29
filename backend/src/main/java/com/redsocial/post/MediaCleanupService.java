package com.redsocial.post;

import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

@ApplicationScoped
public class MediaCleanupService {
    private static final Logger LOG = Logger.getLogger(MediaCleanupService.class);

    @Inject MediaStorage mediaStorage;
    @Inject PostRepository posts;

    public void deleteOrQueue(String key) {
        try {
            mediaStorage.delete(key);
        } catch (RuntimeException ex) {
            try {
                posts.queueMediaCleanup(key);
            } catch (RuntimeException queueFailure) {
                LOG.errorf(queueFailure, "Could not queue media cleanup for key %s", key);
            }
            LOG.warnf("Media deletion deferred for key %s: %s", key, ex.getMessage());
        }
    }

    public void deleteQueued(String key) {
        try {
            mediaStorage.delete(key);
            posts.completeMediaCleanup(key);
        } catch (RuntimeException ex) {
            LOG.warnf("Media cleanup remains queued for key %s: %s", key, ex.getMessage());
        }
    }

    @Priority(3)
    void retryOnStart(@Observes StartupEvent event) {
        try {
            for (String key : posts.pendingMediaCleanup()) deleteQueued(key);
        } catch (RuntimeException ex) {
            LOG.warnf("Media cleanup queue retry failed: %s", ex.getMessage());
        }
    }
}
