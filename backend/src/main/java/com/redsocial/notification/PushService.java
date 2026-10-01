package com.redsocial.notification;

import com.redsocial.common.CurrentUser;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ServiceUnavailableException;
import org.jboss.logging.Logger;

import java.net.URI;
import java.util.Base64;

@ApplicationScoped
public class PushService {
    private static final Logger LOG = Logger.getLogger(PushService.class);
    @Inject CurrentUser currentUser;
    @Inject PushSubscriptionRepository subscriptions;
    @Inject WebPushSender sender;

    public String publicKey() {
        if (!sender.configured()) throw new ServiceUnavailableException("Web Push is not configured");
        return sender.publicKey();
    }

    public void subscribe(String endpoint, String p256dh, String auth, String userAgent) {
        validate(endpoint, p256dh, auth);
        subscriptions.subscribe(currentUser.id(), endpoint, p256dh, auth,
                userAgent == null ? "" : userAgent.substring(0, Math.min(userAgent.length(), 255)));
    }

    public void unsubscribe(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) throw new BadRequestException("Endpoint is required");
        subscriptions.unsubscribe(currentUser.id(), endpoint);
    }

    private void validate(String endpoint, String p256dh, String auth) {
        if (!sender.configured()) throw new ServiceUnavailableException("Web Push is not configured");
        if (endpoint == null || p256dh == null || auth == null)
            throw new BadRequestException("Invalid browser subscription");
        if (endpoint.length() > 2048) throw new BadRequestException("Invalid push endpoint");
        try {
            URI uri = URI.create(endpoint);
            String host = uri.getHost();
            if (!"https".equals(uri.getScheme()) || host == null || uri.getUserInfo() != null ||
                    uri.getPort() != -1 && uri.getPort() != 443 || uri.getFragment() != null)
                throw new IllegalArgumentException();
            if (!allowedEndpoint(uri, host.toLowerCase())) {
                LOG.warnf("Unsupported Web Push service host: %s", host);
                throw new IllegalArgumentException();
            }
            int publicKeyBytes = Base64.getUrlDecoder().decode(p256dh).length;
            int authBytes = Base64.getUrlDecoder().decode(auth).length;
            if (publicKeyBytes != 65 || authBytes != 16) {
                LOG.warnf("Invalid Web Push key lengths: %d/%d", publicKeyBytes, authBytes);
                throw new IllegalArgumentException();
            }
        } catch (RuntimeException ex) {
            throw new BadRequestException("Invalid browser subscription");
        }
    }

    private boolean allowedEndpoint(URI uri, String host) {
        if (host.equals("jmt17.google.com")) {
            String path = uri.normalize().getPath();
            return uri.getQuery() == null && path != null && path.startsWith("/fcm/send/");
        }
        return host.equals("fcm.googleapis.com") || host.equals("fcmregistrations.googleapis.com") ||
                host.equals("updates.push.services.mozilla.com") || host.equals("web.push.apple.com") ||
                host.equals("wns.notify.windows.com");
    }
}
