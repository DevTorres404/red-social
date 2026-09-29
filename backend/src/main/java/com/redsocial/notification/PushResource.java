package com.redsocial.notification;

import com.redsocial.common.CurrentUser;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.net.URI;
import java.util.Base64;
import java.util.Map;

@Path("/api/push")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("user")
public class PushResource {
    private static final Logger LOG = Logger.getLogger(PushResource.class);
    @Inject CurrentUser currentUser;
    @Inject PushSubscriptionRepository subscriptions;
    @Inject WebPushSender sender;

    public record Keys(String p256dh, String auth) {}
    public record SubscriptionRequest(String endpoint, Keys keys) {}
    public record EndpointRequest(String endpoint) {}

    @GET @Path("/public-key")
    public Map<String, String> publicKey() {
        if (!sender.configured()) throw new ServiceUnavailableException("Web Push is not configured");
        return Map.of("publicKey", sender.publicKey());
    }

    @POST @Path("/subscriptions")
    public Response subscribe(SubscriptionRequest request, @HeaderParam("User-Agent") String userAgent) {
        validate(request);
        subscriptions.subscribe(currentUser.id(), request.endpoint(), request.keys().p256dh(),
                request.keys().auth(), userAgent == null ? "" : userAgent.substring(0, Math.min(userAgent.length(), 255)));
        return Response.noContent().build();
    }

    @DELETE @Path("/subscriptions")
    public Response unsubscribe(EndpointRequest request) {
        if (request == null || request.endpoint() == null || request.endpoint().isBlank())
            throw new BadRequestException("Endpoint is required");
        subscriptions.unsubscribe(currentUser.id(), request.endpoint());
        return Response.noContent().build();
    }

    private void validate(SubscriptionRequest request) {
        if (!sender.configured()) throw new ServiceUnavailableException("Web Push is not configured");
        if (request == null || request.endpoint() == null || request.keys() == null)
            throw new BadRequestException("Invalid browser subscription");
        String endpoint = request.endpoint();
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
            int publicKeyBytes = Base64.getUrlDecoder().decode(request.keys().p256dh()).length;
            int authBytes = Base64.getUrlDecoder().decode(request.keys().auth()).length;
            if (publicKeyBytes != 65 || authBytes != 16) {
                LOG.warnf("Invalid Web Push key lengths: %d/%d", publicKeyBytes, authBytes);
                throw new IllegalArgumentException();
            }
        } catch (RuntimeException ex) {
            throw new BadRequestException("Invalid browser subscription");
        }
    }

    private boolean allowedEndpoint(URI uri, String host) {
        // Chromium still issues this exact legacy Google Push endpoint on some builds.
        if (host.equals("jmt17.google.com")) {
            String path = uri.normalize().getPath();
            return uri.getQuery() == null && path != null && path.startsWith("/fcm/send/");
        }
        return host.equals("fcm.googleapis.com") || host.equals("fcmregistrations.googleapis.com") ||
                host.equals("updates.push.services.mozilla.com") || host.equals("web.push.apple.com") ||
                host.equals("wns.notify.windows.com");
    }
}
