package com.redsocial.graph;

import com.redsocial.common.CurrentUser;
import com.redsocial.user.UserRepository;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

@Path("/api/graph")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("user")
public class GraphResource {
    @Inject GraphRepository graph;
    @Inject UserRepository users;
    @Inject CurrentUser currentUser;

    @GET @Path("/common/{otherId}")
    public List<GraphRepository.Person> common(@PathParam("otherId") String otherId) {
        users.findById(otherId).orElseThrow(() -> new NotFoundException("User not found"));
        return graph.commonFollowing(currentUser.id(), otherId);
    }

    @GET @Path("/reachable")
    public List<GraphRepository.Reachable> reachable() { return graph.reachable(currentUser.id()); }

    @GET @Path("/recommendations")
    public List<GraphRepository.Recommendation> recommendations() { return graph.recommendations(currentUser.id()); }

    @GET @Path("/network-posts")
    public List<GraphRepository.NetworkPost> networkPosts() { return graph.networkPosts(currentUser.id()); }

    @GET @Path("/trending-posts")
    public List<GraphRepository.NetworkPost> trendingPosts() { return graph.trendingPosts(currentUser.id()); }
}
