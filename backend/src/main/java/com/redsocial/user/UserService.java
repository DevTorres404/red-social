package com.redsocial.user;

import com.redsocial.common.CurrentUser;
import com.redsocial.notification.InAppNotificationService;
import com.redsocial.notification.PushNotificationService;
import com.redsocial.post.MediaAsset;
import com.redsocial.post.MediaStorage;
import com.redsocial.post.Post;
import com.redsocial.post.PostRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotFoundException;
import org.jboss.resteasy.reactive.multipart.FileUpload;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Profile visibility, social connections and follow orchestration. */
@ApplicationScoped
public class UserService {
    private static final int CONNECTION_PAGE_SIZE = 4;
    public record ConnectionsPage(List<User.UserProfile> users, long total, int page, int size) {}

    @Inject UserRepository users;
    @Inject CurrentUser currentUser;
    @Inject PostRepository posts;
    @Inject PushNotificationService pushNotifications;
    @Inject InAppNotificationService inAppNotifications;
    @Inject MediaStorage mediaStorage;

    public User.UserProfile mySettings() {
        return users.findById(currentUser.id()).orElseThrow().toProfile();
    }

    public User.UserProfile updateSettings(boolean profilePublic, boolean avatarFollowersOnly,
            boolean circleFollowersOnly, boolean followersFollowersOnly, boolean bioFollowersOnly,
            boolean messagesFollowersOnly, boolean interactionsFollowersOnly, String instagram,
            String reddit, String discord) {
        return users.updateSettings(currentUser.id(), profilePublic, avatarFollowersOnly,
                circleFollowersOnly, followersFollowersOnly, bioFollowersOnly, messagesFollowersOnly,
                interactionsFollowersOnly, cleanSocial(instagram), cleanSocial(reddit), cleanSocial(discord)).toProfile();
    }

    private String cleanSocial(String value) {
        return value == null ? "" : value.trim().replaceFirst("^@", "");
    }

    public Optional<User.UserProfile> getProfile(String id) {
        return users.findById(id)
                .filter(owner -> users.canViewProfile(currentUser.id(), owner))
                .map(owner -> visibleProfile(owner, users.isFollowing(currentUser.id(), id)));
    }

    private User.UserProfile visibleProfile(User user, boolean follows) {
        boolean owner = user.id().equals(currentUser.id());
        boolean hideBio = user.bioFollowersOnly() && !follows && !owner;
        return new User.UserProfile(user.id(), user.username(), hideBio ? "" : user.bio(),
                user.avatarFollowersOnly() && !follows && !owner ? "" : user.avatarUrl(), user.createdAt(),
                user.profilePublic(), user.avatarFollowersOnly(), user.circleFollowersOnly(),
                user.followersFollowersOnly(), user.bioFollowersOnly(), user.messagesFollowersOnly(),
                user.interactionsFollowersOnly(), hideBio ? "" : user.instagram(),
                hideBio ? "" : user.reddit(), hideBio ? "" : user.discord());
    }

    /** Empty means the caller is not the profile owner; the HTTP adapter keeps the existing 403. */
    public Optional<User.UserProfile> updateProfile(String id, String bio, String avatarUrl) {
        if (!id.equals(currentUser.id())) return Optional.empty();
        User existing = users.findById(id).orElseThrow(() -> new NotFoundException("User not found: " + id));
        if (bio != null && bio.trim().split("\\s+").length > 160)
            throw new BadRequestException("La biografía no puede superar 160 palabras");
        return Optional.of(users.update(id, bio != null ? bio : existing.bio(),
                avatarUrl != null ? avatarUrl : existing.avatarUrl()).toProfile());
    }

    public Optional<User.UserProfile> uploadAvatar(String id, List<FileUpload> files) {
        if (!id.equals(currentUser.id())) return Optional.empty();
        if (files == null || files.isEmpty() || !"image".equals(files.get(0).name()))
            throw new BadRequestException("Exactly one image is required");
        User existing = users.findById(id).orElseThrow(() -> new NotFoundException("User not found: " + id));
        MediaAsset image = mediaStorage.upload(files.get(0), "avatars");
        String publicUrl = mediaStorage.publicUrl(image.key());
        return Optional.of(users.update(id, existing.bio(), publicUrl).toProfile());
    }

    public List<Post> getPosts(String id) {
        User owner = users.findById(id).orElseThrow(() -> new NotFoundException("User not found: " + id));
        if (!users.canViewProfile(currentUser.id(), owner)) throw new NotFoundException("User not found: " + id);
        return posts.findByAuthor(id, currentUser.id());
    }

    public List<User.UserProfile> getFollowers(String id) {
        requireCircleVisible(id, true);
        return users.findFollowers(id).stream().filter(user -> users.canViewProfile(currentUser.id(), user))
                .map(user -> visibleProfile(user, users.isFollowing(currentUser.id(), user.id()))).toList();
    }

    public List<User.UserProfile> getFollowing(String id) {
        requireCircleVisible(id, false);
        return users.findFollowing(id).stream().filter(user -> users.canViewProfile(currentUser.id(), user))
                .map(user -> visibleProfile(user, users.isFollowing(currentUser.id(), user.id()))).toList();
    }

    private void requireCircleVisible(String id, boolean followers) {
        User owner = users.findById(id).orElseThrow(() -> new NotFoundException("User not found"));
        boolean restricted = followers ? owner.followersFollowersOnly() : owner.circleFollowersOnly();
        if (!users.canViewProfile(currentUser.id(), owner) || (restricted
                && !id.equals(currentUser.id()) && !users.isFollowing(currentUser.id(), id)))
            throw new NotFoundException("User not found");
    }

    public ConnectionsPage connectionsPage(String id, int page, boolean followers) {
        validateConnectionsPage(page);
        User owner = users.findById(id).orElseThrow(() -> new NotFoundException("User not found: " + id));
        boolean restricted = followers ? owner.followersFollowersOnly() : owner.circleFollowersOnly();
        if (!users.canViewProfile(currentUser.id(), owner)) throw new NotFoundException("User not found: " + id);
        if (restricted && !users.isFollowing(currentUser.id(), id) && !id.equals(currentUser.id()))
            return new ConnectionsPage(List.of(), 0, page, CONNECTION_PAGE_SIZE);
        var result = users.findConnectionsPage(id, followers, page, CONNECTION_PAGE_SIZE);
        return new ConnectionsPage(result.users().stream().filter(user -> users.canViewProfile(currentUser.id(), user))
                .map(user -> visibleProfile(user, users.isFollowing(currentUser.id(), user.id()))).toList(),
                result.total(), page, CONNECTION_PAGE_SIZE);
    }

    public static void validateConnectionsPage(int page) {
        if (page < 0 || page > 10_000) throw new BadRequestException("Invalid page");
    }

    public List<User.UserProfile> discover() {
        return users.findDiscoverable(currentUser.id()).stream()
                .map(user -> visibleProfile(user, users.isFollowing(currentUser.id(), user.id()))).toList();
    }

    public Map<String, Boolean> followStatus(String id) {
        users.findById(id).orElseThrow(() -> new NotFoundException("User not found: " + id));
        return Map.of("following", users.isFollowing(currentUser.id(), id));
    }

    /** False preserves the existing bad-request response for self-follow. */
    public boolean follow(String followedId) {
        String followerId = currentUser.id();
        if (followerId.equals(followedId)) return false;
        users.follow(followerId, followedId);
        inAppNotifications.onUserFollowed(followerId, followedId);
        pushNotifications.onUserFollowed(followerId, followedId);
        return true;
    }

    public void unfollow(String followedId) {
        users.unfollow(currentUser.id(), followedId);
    }

    public List<User.UserProfile> getSuggestions(String id) {
        if (!id.equals(currentUser.id())) throw new ForbiddenException("Cannot view another user's suggestions");
        return users.findSuggestions(id).stream()
                .map(user -> visibleProfile(user, users.isFollowing(currentUser.id(), user.id()))).toList();
    }

    public List<User.UserProfile> search(String query) {
        if (query == null || query.trim().isEmpty()) return List.of();
        return users.search(query, currentUser.id()).stream()
                .map(user -> visibleProfile(user, users.isFollowing(currentUser.id(), user.id()))).toList();
    }
}
