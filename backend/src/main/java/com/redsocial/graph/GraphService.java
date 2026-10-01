package com.redsocial.graph;

import com.redsocial.common.CurrentUser;
import com.redsocial.user.UserRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;

import java.util.List;

@ApplicationScoped
public class GraphService {
    @Inject GraphRepository graph;
    @Inject UserRepository users;
    @Inject CurrentUser currentUser;

    public List<GraphRepository.Person> common(String otherId) {
        users.findById(otherId).orElseThrow(() -> new NotFoundException("User not found"));
        return graph.commonFollowing(currentUser.id(), otherId);
    }

    public List<GraphRepository.Reachable> reachable() { return graph.reachable(currentUser.id()); }
    public List<GraphRepository.Recommendation> recommendations() { return graph.recommendations(currentUser.id()); }
    public List<GraphRepository.NetworkPost> networkPosts() { return graph.networkPosts(currentUser.id()); }
    public List<GraphRepository.NetworkPost> trendingPosts() { return graph.trendingPosts(currentUser.id()); }
}
