package com.redsocial.feed;

import com.redsocial.common.CurrentUser;
import com.redsocial.post.Post;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.time.Instant;
import java.util.List;

@ApplicationScoped
public class FeedService {
    @Inject FeedRepository feed;
    @Inject FeedTickets tickets;
    @Inject CurrentUser currentUser;
    @Inject JsonWebToken jwt;

    public String issueTicket() {
        return tickets.issue(currentUser.id(), Instant.ofEpochSecond(jwt.getExpirationTime())).ticket();
    }

    public List<Post> getHomeFeed(int skip, int limit) {
        validatePage(skip, limit);
        return feed.getHomeFeed(currentUser.id(), skip, limit);
    }

    public List<Post> getExploreFeed(int skip, int limit) {
        validatePage(skip, limit);
        return feed.getExploreFeed(currentUser.id(), skip, limit);
    }

    public static void validatePage(int skip, int limit) {
        if (skip < 0 || skip > 10_000 || limit < 1 || limit > 100)
            throw new BadRequestException("skip must be 0..10000 and limit must be 1..100");
    }
}
