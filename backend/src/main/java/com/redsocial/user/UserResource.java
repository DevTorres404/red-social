package com.redsocial.user;

import com.redsocial.common.CurrentUser;
import com.redsocial.notification.InAppNotificationService;
import com.redsocial.notification.PushNotificationService;
import com.redsocial.post.Post;
import com.redsocial.post.PostRepository;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import com.redsocial.post.MediaAsset;
import com.redsocial.post.MediaStorage;
import org.jboss.resteasy.reactive.multipart.FileUpload;
import org.jboss.resteasy.reactive.RestForm;
import java.util.List;
import java.util.Map;

@Path("/api/users")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("user")
@Tag(name = "Users & Social Graph")
public class UserResource {

    private static final int CONNECTION_PAGE_SIZE = 4;

    public record ConnectionsPage(List<User.UserProfile> users, long total, int page, int size) {}

    @Inject
    UserRepository userRepository;

    @Inject
    CurrentUser currentUser;

    @Inject
    PostRepository postRepository;

    @Inject
    PushNotificationService pushNotifications;

    @Inject
    InAppNotificationService inAppNotifications;

    @Inject
    MediaStorage mediaStorage;

    public record UpdateProfileRequest(@Size(max = 160) String bio,
                                       @Size(max = 2048) String avatarUrl) {}

    @GET
    @Path("/{id}")
    @Operation(summary = "Get user profile by ID")
    public Response getProfile(@PathParam("id") String id) {
        return userRepository.findById(id)
                .map(User::toProfile)
                .map(profile -> Response.ok(profile).build())
                .orElse(Response.status(Response.Status.NOT_FOUND).build());
    }

    @PUT
    @Path("/{id}")
    @Operation(summary = "Update user profile")
    public Response updateProfile(@PathParam("id") String id, @Valid UpdateProfileRequest req) {
        // Only the owner can update their profile
        if (!id.equals(currentUser.id())) {
            return Response.status(Response.Status.FORBIDDEN).build();
        }
        
        User existing = userRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("User not found: " + id));
        User updatedUser = userRepository.update(id,
                req.bio() != null ? req.bio() : existing.bio(),
                req.avatarUrl() != null ? req.avatarUrl() : existing.avatarUrl());
        return Response.ok(updatedUser.toProfile()).build();
    }

    @POST
    @Path("/{id}/avatar")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Operation(summary = "Upload and update user profile avatar")
    public Response uploadAvatar(@PathParam("id") String id, @RestForm(FileUpload.ALL) List<FileUpload> files) {
        if (!id.equals(currentUser.id())) {
            return Response.status(Response.Status.FORBIDDEN).build();
        }
        if (files == null || files.isEmpty() || !"image".equals(files.get(0).name())) {
            throw new BadRequestException("Exactly one image is required");
        }
        
        User existing = userRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("User not found: " + id));
        
        MediaAsset image = mediaStorage.upload(files.get(0), "avatars");
        String publicUrl = mediaStorage.publicUrl(image.key());
        
        User updatedUser = userRepository.update(id, existing.bio(), publicUrl);
        return Response.ok(updatedUser.toProfile()).build();
    }

    @GET
    @Path("/{id}/posts")
    @Operation(summary = "List publications by a user")
    public List<Post> getPosts(@PathParam("id") String id) {
        userRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("User not found: " + id));
        return postRepository.findByAuthor(id, currentUser.id());
    }

    @GET
    @Path("/{id}/followers")
    @Operation(summary = "List followers of a user")
    public List<User.UserProfile> getFollowers(@PathParam("id") String id) {
        return userRepository.findFollowers(id).stream().map(User::toProfile).toList();
    }

    @GET
    @Path("/{id}/followers/page")
    @Operation(summary = "List one page of a user's followers")
    public ConnectionsPage getFollowersPage(@PathParam("id") String id,
                                            @QueryParam("page") @DefaultValue("0") int page) {
        return connectionsPage(id, page, true);
    }

    @GET
    @Path("/{id}/following")
    @Operation(summary = "List users followed by a user")
    public List<User.UserProfile> getFollowing(@PathParam("id") String id) {
        return userRepository.findFollowing(id).stream().map(User::toProfile).toList();
    }

    @GET
    @Path("/{id}/following/page")
    @Operation(summary = "List one page of users followed by a user")
    public ConnectionsPage getFollowingPage(@PathParam("id") String id,
                                            @QueryParam("page") @DefaultValue("0") int page) {
        return connectionsPage(id, page, false);
    }

    private ConnectionsPage connectionsPage(String id, int page, boolean followers) {
        validateConnectionsPage(page);
        userRepository.findById(id).orElseThrow(() -> new NotFoundException("User not found: " + id));
        var result = userRepository.findConnectionsPage(id, followers, page, CONNECTION_PAGE_SIZE);
        return new ConnectionsPage(result.users().stream().map(User::toProfile).toList(),
                result.total(), page, CONNECTION_PAGE_SIZE);
    }

    static void validateConnectionsPage(int page) {
        if (page < 0 || page > 10_000) {
            throw new BadRequestException("Invalid page");
        }
    }

    @GET
    @Path("/discover")
    @Operation(summary = "Discover people not yet followed by the current user")
    public List<User.UserProfile> discover() {
        return userRepository.findDiscoverable(currentUser.id()).stream().map(User::toProfile).toList();
    }

    @GET
    @Path("/{id}/follow-status")
    @Operation(summary = "Check whether the current user follows this profile")
    public Map<String, Boolean> followStatus(@PathParam("id") String id) {
        userRepository.findById(id).orElseThrow(() -> new NotFoundException("User not found: " + id));
        return Map.of("following", userRepository.isFollowing(currentUser.id(), id));
    }

    @POST
    @Path("/{id}/follow")
    @Operation(summary = "Follow a user")
    public Response follow(@PathParam("id") String followedId) {
        String followerId = currentUser.id();
        if (followerId.equals(followedId)) {
            return Response.status(Response.Status.BAD_REQUEST).entity("Cannot follow yourself").build();
        }
        
        userRepository.follow(followerId, followedId);
        inAppNotifications.onUserFollowed(followerId, followedId);
        pushNotifications.onUserFollowed(followerId, followedId);
        return Response.ok().build();
    }

    @DELETE
    @Path("/{id}/follow")
    @Operation(summary = "Unfollow a user")
    public Response unfollow(@PathParam("id") String followedId) {
        String followerId = currentUser.id();
        userRepository.unfollow(followerId, followedId);
        return Response.ok().build();
    }

    @GET
    @Path("/{id}/suggestions")
    @Operation(summary = "Get graph-based follow suggestions")
    public List<User.UserProfile> getSuggestions(@PathParam("id") String id) {
        if (!id.equals(currentUser.id())) {
            throw new ForbiddenException("Cannot view another user's suggestions");
        }
        return userRepository.findSuggestions(id).stream().map(User::toProfile).toList();
    }

    @GET
    @Path("/search")
    @Operation(summary = "Search users by username or bio")
    public List<User.UserProfile> search(@QueryParam("q") String query) {
        if (query == null || query.trim().isEmpty()) {
            return List.of();
        }
        return userRepository.search(query).stream().map(User::toProfile).toList();
    }
}
