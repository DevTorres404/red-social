package com.redsocial.post;

import com.redsocial.common.CurrentUser;
import com.redsocial.feed.FeedDelivery;
import com.redsocial.notification.InAppNotificationService;
import com.redsocial.notification.PushNotificationService;
import com.redsocial.post.dto.CreateCommentRequest;
import com.redsocial.post.dto.CreatePostRequest;
import com.redsocial.post.dto.CommentReactionRequest;
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
import java.util.Set;

/**
 * REST endpoints for (:Post) and (:Comentario) nodes.
 *
 * All endpoints require a valid JWT (@RolesAllowed("user")).
 * The current user's ID comes from CurrentUser (JWT "sub" claim).
 */
@Path("/api/posts")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("user")
@Tag(name = "Posts")
public class PostResource {
    static final Set<String> COMMENT_EMOJIS = Set.of("❤️", "😂", "😍", "😮", "😢", "👏");

    @Inject
    PostRepository postRepository;

    @Inject
    CurrentUser currentUser;

    @Inject
    MediaStorage mediaStorage;

    @Inject
    MediaCleanupService mediaCleanup;

    @Inject
    PushNotificationService pushNotifications;

    @Inject
    InAppNotificationService inAppNotifications;

    @Inject
    FeedDelivery feedDelivery;

    // ── Post CRUD ─────────────────────────────────────────────────────────────

    @POST
    @Operation(summary = "Create a new post")
    public Response create(@Valid CreatePostRequest request) {
        if (request.mediaUrl() != null && !request.mediaUrl().isBlank()) {
            throw new BadRequestException("Use multipart upload for images, not a media URL");
        }
        String userId = currentUser.id();
        Post post = postRepository.create(userId, request.content(), null);
        inAppNotifications.onPostCreated(userId, post.id());
        pushNotifications.onPostCreated(userId, post.id());
        return Response.status(Response.Status.CREATED).entity(post).build();
    }

    @POST
    @Path("/with-image")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Operation(summary = "Create a post with one validated PNG or JPEG image")
    public Response createWithImage(@RestForm String content,
                                    @RestForm(FileUpload.ALL) List<FileUpload> files) {
        if (content == null || content.isBlank() || content.length() > 2000) {
            throw new BadRequestException("Content is required and must not exceed 2000 characters");
        }
        if (files == null || files.size() != 1 || !"image".equals(files.get(0).name())) {
            throw new BadRequestException("Exactly one image is required");
        }
        MediaAsset image = mediaStorage.upload(files.get(0), "posts");
        try {
            Post post = postRepository.create(currentUser.id(), content.trim(), image);
            inAppNotifications.onPostCreated(currentUser.id(), post.id());
            pushNotifications.onPostCreated(currentUser.id(), post.id());
            return Response.status(Response.Status.CREATED).entity(post).build();
        } catch (RuntimeException ex) {
            mediaCleanup.deleteOrQueue(image.key());
            throw ex;
        }
    }

    @GET
    @Path("/{postId}")
    @Operation(summary = "Get a post by ID with like and comment counts")
    public Response findById(@PathParam("postId") String postId) {
        String userId = currentUser.id();
        return postRepository.findById(postId, userId)
                .map(post -> Response.ok(post).build())
                .orElse(Response.status(Response.Status.NOT_FOUND).build());
    }

    @GET
    @Path("/{postId}/media-url")
    @Operation(summary = "Issue a one-minute read URL after authorizing post access")
    public Map<String, String> mediaUrl(@PathParam("postId") String postId) {
        Post post = postRepository.findById(postId, currentUser.id())
                .orElseThrow(() -> new NotFoundException("Post not found: " + postId));
        if (post.mediaKey().isBlank()) throw new NotFoundException("Post has no image");
        return Map.of("url", mediaStorage.presignedReadUrl(post.mediaKey()));
    }

    @DELETE
    @Path("/{postId}")
    @Operation(summary = "Delete a post (author only)")
    public Response delete(@PathParam("postId") String postId) {
        String userId = currentUser.id();
        for (String mediaKey : postRepository.delete(postId, userId)) mediaCleanup.deleteQueued(mediaKey);
        return Response.noContent().build();
    }

    // ── Likes ─────────────────────────────────────────────────────────────────

    @POST
    @Path("/{postId}/like")
    @Operation(summary = "Like a post")
    public Response like(@PathParam("postId") String postId) {
        String userId = currentUser.id();
        boolean changed = postRepository.like(userId, postId);
        postRepository.findById(postId, userId).ifPresent(p -> {
            if (changed) {
                feedDelivery.publishLikeChanged(postId, userId, p.likeCount(), true);
            }
            if (p.authorId() != null) {
                inAppNotifications.onPostLiked(userId, postId, p.authorId());
                // Web Push is not idempotent: a duplicate like must not re-notify.
                if (changed) pushNotifications.onPostLiked(userId, postId, p.authorId());
            }
        });
        return Response.noContent().build();
    }

    @DELETE
    @Path("/{postId}/like")
    @Operation(summary = "Unlike a post")
    public Response unlike(@PathParam("postId") String postId) {
        String userId = currentUser.id();
        boolean changed = postRepository.unlike(userId, postId);
        if (changed) {
            postRepository.findById(postId, userId).ifPresent(p ->
                    feedDelivery.publishLikeChanged(postId, userId, p.likeCount(), false));
        }
        return Response.noContent().build();
    }

    // ── Comments ──────────────────────────────────────────────────────────────

    @GET
    @Path("/{postId}/comments")
    @Operation(summary = "Get all comments for a post")
    public List<Post.Comment> getComments(@PathParam("postId") String postId) {
        postRepository.findById(postId, currentUser.id())
                .orElseThrow(() -> new NotFoundException("Post not found: " + postId));
        return postRepository.findComments(postId, currentUser.id());
    }

    @POST
    @Path("/{postId}/comments")
    @Operation(summary = "Add a comment to a post")
    public Response addComment(@PathParam("postId") String postId,
                               @Valid CreateCommentRequest request) {
        String userId = currentUser.id();
        Post.Comment comment = postRepository.addComment(postId, userId, request.text().trim(), null);
        publishComment(postId, userId, comment);
        return Response.status(Response.Status.CREATED).entity(comment).build();
    }

    @POST
    @Path("/{postId}/comments/with-image")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Operation(summary = "Add a comment with one validated PNG or JPEG image")
    public Response addCommentWithImage(@PathParam("postId") String postId,
                                        @RestForm String text,
                                        @RestForm(FileUpload.ALL) List<FileUpload> files) {
        String content = text == null ? "" : text.trim();
        if (content.length() > 500) throw new BadRequestException("Comment cannot exceed 500 characters");
        if (files == null || files.size() != 1 || !"image".equals(files.get(0).name())) {
            throw new BadRequestException("Exactly one image is required");
        }
        postRepository.findById(postId, currentUser.id())
                .orElseThrow(() -> new NotFoundException("Post not found: " + postId));
        MediaAsset image = mediaStorage.upload(files.get(0), "comments");
        String userId = currentUser.id();
        Post.Comment comment;
        try {
            comment = postRepository.addComment(postId, userId, content, image);
        } catch (RuntimeException ex) {
            mediaCleanup.deleteOrQueue(image.key());
            throw ex;
        }
        publishComment(postId, userId, comment);
        return Response.status(Response.Status.CREATED).entity(comment).build();
    }

    @GET
    @Path("/{postId}/comments/{commentId}/media-url")
    @Operation(summary = "Issue a short-lived read URL for a comment image")
    public Map<String, String> commentMediaUrl(@PathParam("postId") String postId,
                                               @PathParam("commentId") String commentId) {
        Post.Comment comment = postRepository.findComment(postId, commentId, currentUser.id());
        if (comment.mediaKey().isBlank()) throw new NotFoundException("Comment has no image");
        return Map.of("url", mediaStorage.presignedReadUrl(comment.mediaKey()));
    }

    @PUT
    @Path("/{postId}/comments/{commentId}/reaction")
    @Operation(summary = "Set one emoji reaction on a comment")
    public Post.Comment reactToComment(@PathParam("postId") String postId,
                                       @PathParam("commentId") String commentId,
                                       CommentReactionRequest request) {
        if (request == null || !COMMENT_EMOJIS.contains(request.emoji())) {
            throw new BadRequestException("Unsupported comment reaction");
        }
        String userId = currentUser.id();
        postRepository.setCommentReaction(postId, commentId, userId, request.emoji());
        Post.Comment comment = postRepository.findComment(postId, commentId, userId);
        feedDelivery.publishCommentReactionChanged(postId, commentId, userId, comment.reactions(), request.emoji());
        return comment;
    }

    @DELETE
    @Path("/{postId}/comments/{commentId}/reaction")
    @Operation(summary = "Remove my emoji reaction from a comment")
    public Post.Comment removeCommentReaction(@PathParam("postId") String postId,
                                              @PathParam("commentId") String commentId) {
        String userId = currentUser.id();
        postRepository.removeCommentReaction(postId, commentId, userId);
        Post.Comment comment = postRepository.findComment(postId, commentId, userId);
        feedDelivery.publishCommentReactionChanged(postId, commentId, userId, comment.reactions(), "");
        return comment;
    }

    private void publishComment(String postId, String userId, Post.Comment comment) {
        postRepository.findById(postId, userId).ifPresent(post -> {
            feedDelivery.publishCommentCreated(postId, comment, post.commentCount());
            inAppNotifications.onPostCommented(userId, postId, comment.id(), post.authorId());
        });
    }
}
