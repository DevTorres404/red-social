package com.redsocial.notification;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Map;

@Path("/api/push")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("user")
public class PushResource {
    @Inject PushService service;

    public record Keys(String p256dh, String auth) {}
    public record SubscriptionRequest(String endpoint, Keys keys) {}
    public record EndpointRequest(String endpoint) {}

    @GET @Path("/public-key")
    public Map<String, String> publicKey() {
        return Map.of("publicKey", service.publicKey());
    }

    @POST @Path("/subscriptions")
    public Response subscribe(SubscriptionRequest request, @HeaderParam("User-Agent") String userAgent) {
        service.subscribe(request == null ? null : request.endpoint(),
                request == null || request.keys() == null ? null : request.keys().p256dh(),
                request == null || request.keys() == null ? null : request.keys().auth(), userAgent);
        return Response.noContent().build();
    }

    @DELETE @Path("/subscriptions")
    public Response unsubscribe(EndpointRequest request) {
        service.unsubscribe(request == null ? null : request.endpoint());
        return Response.noContent().build();
    }

}
