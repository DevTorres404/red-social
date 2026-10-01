package com.redsocial.user;

import com.redsocial.post.Post;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
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
    @Inject UserService service;

    public record ConnectionsPage(List<User.UserProfile> users, long total, int page, int size) {}
    public record UpdateProfileRequest(String bio, @Size(max = 2048) String avatarUrl) {}
    public record SettingsRequest(boolean profilePublic, boolean avatarFollowersOnly,
            boolean circleFollowersOnly, boolean followersFollowersOnly, boolean bioFollowersOnly, boolean messagesFollowersOnly,
            boolean interactionsFollowersOnly, @Size(max=40) String instagram,
            @Size(max=40) String reddit, @Size(max=40) String discord) {}

    @GET @Path("/me/settings")
    public User.UserProfile mySettings() {
        return service.mySettings();
    }

    @PUT @Path("/me/settings")
    public User.UserProfile updateSettings(@Valid SettingsRequest req) {
        return service.updateSettings(req.profilePublic(), req.avatarFollowersOnly(), req.circleFollowersOnly(),
                req.followersFollowersOnly(), req.bioFollowersOnly(), req.messagesFollowersOnly(),
                req.interactionsFollowersOnly(), req.instagram(), req.reddit(), req.discord());
    }

    @GET
    @Path("/{id}")
    @Operation(summary = "Get user profile by ID")
    public Response getProfile(@PathParam("id") String id) {
        return service.getProfile(id).map(profile -> Response.ok(profile).build())
                .orElse(Response.status(Response.Status.NOT_FOUND).build());
    }

    @PUT
    @Path("/{id}")
    @Operation(summary = "Update user profile")
    public Response updateProfile(@PathParam("id") String id, @Valid UpdateProfileRequest req) {
        return service.updateProfile(id, req.bio(), req.avatarUrl())
                .map(profile -> Response.ok(profile).build())
                .orElse(Response.status(Response.Status.FORBIDDEN).build());
    }

    @POST
    @Path("/{id}/avatar")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Operation(summary = "Upload and update user profile avatar")
    public Response uploadAvatar(@PathParam("id") String id, @RestForm(FileUpload.ALL) List<FileUpload> files) {
        return service.uploadAvatar(id, files)
                .map(profile -> Response.ok(profile).build())
                .orElse(Response.status(Response.Status.FORBIDDEN).build());
    }

    @GET
    @Path("/{id}/posts")
    @Operation(summary = "List publications by a user")
    public List<Post> getPosts(@PathParam("id") String id) {
        return service.getPosts(id);
    }

    @GET
    @Path("/{id}/followers")
    @Operation(summary = "List followers of a user")
    public List<User.UserProfile> getFollowers(@PathParam("id") String id) {
        return service.getFollowers(id);
    }

    @GET
    @Path("/{id}/followers/page")
    @Operation(summary = "List one page of a user's followers")
    public ConnectionsPage getFollowersPage(@PathParam("id") String id,
                                            @QueryParam("page") @DefaultValue("0") int page) {
        return connectionPage(service.connectionsPage(id, page, true));
    }

    @GET
    @Path("/{id}/following")
    @Operation(summary = "List users followed by a user")
    public List<User.UserProfile> getFollowing(@PathParam("id") String id) {
        return service.getFollowing(id);
    }

    @GET
    @Path("/{id}/following/page")
    @Operation(summary = "List one page of users followed by a user")
    public ConnectionsPage getFollowingPage(@PathParam("id") String id,
                                            @QueryParam("page") @DefaultValue("0") int page) {
        return connectionPage(service.connectionsPage(id, page, false));
    }

    private ConnectionsPage connectionPage(UserService.ConnectionsPage page) {
        return new ConnectionsPage(page.users(), page.total(), page.page(), page.size());
    }

    @GET
    @Path("/discover")
    @Operation(summary = "Discover people not yet followed by the current user")
    public List<User.UserProfile> discover() {
        return service.discover();
    }

    @GET
    @Path("/{id}/follow-status")
    @Operation(summary = "Check whether the current user follows this profile")
    public Map<String, Boolean> followStatus(@PathParam("id") String id) {
        return service.followStatus(id);
    }

    @POST
    @Path("/{id}/follow")
    @Operation(summary = "Follow a user")
    public Response follow(@PathParam("id") String followedId) {
        if (!service.follow(followedId))
            return Response.status(Response.Status.BAD_REQUEST).entity("Cannot follow yourself").build();
        return Response.ok().build();
    }

    @DELETE
    @Path("/{id}/follow")
    @Operation(summary = "Unfollow a user")
    public Response unfollow(@PathParam("id") String followedId) {
        service.unfollow(followedId);
        return Response.ok().build();
    }

    @GET
    @Path("/{id}/suggestions")
    @Operation(summary = "Get graph-based follow suggestions")
    public List<User.UserProfile> getSuggestions(@PathParam("id") String id) {
        return service.getSuggestions(id);
    }

    @GET
    @Path("/search")
    @Operation(summary = "Search users by username or bio")
    public List<User.UserProfile> search(@QueryParam("q") String query) {
        return service.search(query);
    }
}
