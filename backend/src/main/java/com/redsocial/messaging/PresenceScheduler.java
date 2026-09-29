package com.redsocial.messaging;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

@ApplicationScoped
public class PresenceScheduler {

    private static final Logger LOG = Logger.getLogger(PresenceScheduler.class);

    @Inject
    PresenceService presence;

    // Run cleanup every 15 seconds
    @Scheduled(every = "15s", identity = "presence-cleanup")
    void cleanup() {
        presence.cleanupStaleConnections();
    }
}