package com.redsocial.graph;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

@Path("/api/graph")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("user")
public class GraphResource {
    @Inject GraphService service;

    @GET @Path("/common/{otherId}")
    public List<GraphRepository.Person> common(@PathParam("otherId") String otherId) {
        return service.common(otherId);
    }

    @GET @Path("/reachable")
    public List<GraphRepository.Reachable> reachable() { return service.reachable(); }

    @GET @Path("/recommendations")
    public List<GraphRepository.Recommendation> recommendations() { return service.recommendations(); }

    @GET @Path("/network-posts")
    public List<GraphRepository.NetworkPost> networkPosts() { return service.networkPosts(); }

    @GET @Path("/trending-posts")
    public List<GraphRepository.NetworkPost> trendingPosts() { return service.trendingPosts(); }
}
