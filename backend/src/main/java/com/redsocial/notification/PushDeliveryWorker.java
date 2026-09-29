package com.redsocial.notification;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

@ApplicationScoped
public class PushDeliveryWorker {
    private static final Logger LOG = Logger.getLogger(PushDeliveryWorker.class);
    @Inject PushDeliveryRepository deliveries;
    @Inject WebPushSender sender;

    @Scheduled(every = "10s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void dispatch() {
        if (!sender.configured()) return;
        for (var job : deliveries.due()) {
            try {
                if (!deliveries.claim(job.id())) continue;
                var subscription = deliveries.authorizedSubscription(job.id());
                if (subscription.isEmpty()) { deliveries.complete(job.id()); continue; }
                String payload = job.payload() != null ? job.payload()
                        : "{\"type\":\"POST_CREATED\",\"refId\":\"" + job.refId() + "\",\"url\":\"/posts/" + job.refId() + "\"}";
                int status = sender.send(subscription.get(), payload);
                if (status >= 200 && status < 300) deliveries.complete(job.id());
                else if (status == 404 || status == 410) deliveries.removeSubscription(subscription.get().id());
                else deliveries.retry(job.id(), job.attempts() + 1);
            } catch (Exception ex) {
                LOG.warnf("Web Push delivery failed for job %s: %s - %s", job.id(), ex.getClass().getSimpleName(), ex.getMessage());
                deliveries.retry(job.id(), job.attempts() + 1);
            }
        }
    }
}
