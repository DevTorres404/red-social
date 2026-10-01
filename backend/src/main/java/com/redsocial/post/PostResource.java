package com.redsocial.post;

import com.redsocial.post.dto.CommentReactionRequest;
import com.redsocial.post.dto.CreateCommentRequest;
import com.redsocial.post.dto.CreatePostRequest;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;

import java.util.List;
import java.util.Map;

/** HTTP adapter for posts, likes and comments. */
@Path("/api/posts")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("user")
@Tag(name = "Posts")
public class PostResource {
    @Inject PostService service;

    @POST
    @Operation(summary = "Create a new post")
    public Response create(@Valid CreatePostRequest request) {
        return Response.status(Response.Status.CREATED).entity(service.create(request)).build();
    }

    @POST
    @Path("/with-image")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Operation(summary = "Create a post with one validated PNG or JPEG image")
    public Response createWithImage(@RestForm String content,
                                    @RestForm(FileUpload.ALL) List<FileUpload> files) {
        return Response.status(Response.Status.CREATED).entity(service.createWithImage(content, files)).build();
    }

    @GET
    @Path("/{postId}")
    @Operation(summary = "Get a post by ID with like and comment counts")
    public Response findById(@PathParam("postId") String postId) {
        return service.findById(postId)
                .map(post -> Response.ok(post).build())
                .orElse(Response.status(Response.Status.NOT_FOUND).build());
    }

    @GET
    @Path("/{postId}/media-url")
    @Operation(summary = "Issue a one-minute read URL after authorizing post access")
    public Map<String, String> mediaUrl(@PathParam("postId") String postId) {
        return Map.of("url", service.mediaUrl(postId));
    }

    @DELETE
    @Path("/{postId}")
    @Operation(summary = "Delete a post (author only)")
    public Response delete(@PathParam("postId") String postId) {
        service.delete(postId);
        return Response.noContent().build();
    }

    @POST
    @Path("/{postId}/like")
    @Operation(summary = "Like a post")
    public Response like(@PathParam("postId") String postId) {
        service.like(postId);
        return Response.noContent().build();
    }

    @DELETE
    @Path("/{postId}/like")
    @Operation(summary = "Unlike a post")
    public Response unlike(@PathParam("postId") String postId) {
        service.unlike(postId);
        return Response.noContent().build();
    }

    @GET
    @Path("/{postId}/comments")
    @Operation(summary = "Get all comments for a post")
    public List<Post.Comment> getComments(@PathParam("postId") String postId) {
        return service.getComments(postId);
    }

    @POST
    @Path("/{postId}/comments")
    @Operation(summary = "Add a comment to a post")
    public Response addComment(@PathParam("postId") String postId,
                               @Valid CreateCommentRequest request) {
        return Response.status(Response.Status.CREATED).entity(service.addComment(postId, request)).build();
    }

    @POST
    @Path("/{postId}/comments/with-image")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Operation(summary = "Add a comment with one validated PNG or JPEG image")
    public Response addCommentWithImage(@PathParam("postId") String postId,
                                        @RestForm String text,
                                        @RestForm(FileUpload.ALL) List<FileUpload> files) {
        return Response.status(Response.Status.CREATED).entity(service.addCommentWithImage(postId, text, files)).build();
    }

    @GET
    @Path("/{postId}/comments/{commentId}/media-url")
    @Operation(summary = "Issue a short-lived read URL for a comment image")
    public Map<String, String> commentMediaUrl(@PathParam("postId") String postId,
                                               @PathParam("commentId") String commentId) {
        return Map.of("url", service.commentMediaUrl(postId, commentId));
    }

    @PUT
    @Path("/{postId}/comments/{commentId}/reaction")
    @Operation(summary = "Set one emoji reaction on a comment")
    public Post.Comment reactToComment(@PathParam("postId") String postId,
                                       @PathParam("commentId") String commentId,
                                       CommentReactionRequest request) {
        return service.reactToComment(postId, commentId, request);
    }

    @DELETE
    @Path("/{postId}/comments/{commentId}/reaction")
    @Operation(summary = "Remove my emoji reaction from a comment")
    public Post.Comment removeCommentReaction(@PathParam("postId") String postId,
                                              @PathParam("commentId") String commentId) {
        return service.removeCommentReaction(postId, commentId);
    }
}
