package com.redsocial.post;

import com.redsocial.common.CurrentUser;
import com.redsocial.feed.FeedDelivery;
import com.redsocial.notification.InAppNotificationService;
import com.redsocial.notification.PushNotificationService;
import com.redsocial.post.dto.CommentReactionRequest;
import com.redsocial.post.dto.CreateCommentRequest;
import com.redsocial.post.dto.CreatePostRequest;
import com.redsocial.user.UserRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotFoundException;
import org.jboss.resteasy.reactive.multipart.FileUpload;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Business rules and orchestration for posts, likes and comments. */
@ApplicationScoped
public class PostService {
    private static final Set<String> COMMENT_EMOJIS = Set.of("❤️", "😂", "😍", "😮", "😢", "👏");

    @Inject PostRepository posts;
    @Inject UserRepository users;
    @Inject CurrentUser currentUser;
    @Inject MediaStorage mediaStorage;
    @Inject MediaCleanupService mediaCleanup;
    @Inject PushNotificationService pushNotifications;
    @Inject InAppNotificationService inAppNotifications;
    @Inject FeedDelivery feedDelivery;

    private Post requirePostVisible(String postId) {
        Post post = posts.findById(postId, currentUser.id())
                .orElseThrow(() -> new NotFoundException("Post not found"));
        var author = users.findById(post.authorId()).orElseThrow(() -> new NotFoundException("Post not found"));
        if (!users.canViewProfile(currentUser.id(), author))
            throw new NotFoundException("Post not found");
        return post;
    }

    private void requireInteractionPermission(String postId) {
        Post post = requirePostVisible(postId);
        var author = users.findById(post.authorId()).orElseThrow();
        if (author.interactionsFollowersOnly() && !author.id().equals(currentUser.id())
                && !users.isFollowing(currentUser.id(), author.id()))
            throw new ForbiddenException("Solo los seguidores pueden interactuar con esta publicación");
    }

    public Post create(CreatePostRequest request) {
        if (request.mediaUrl() != null && !request.mediaUrl().isBlank())
            throw new BadRequestException("Use multipart upload for images, not a media URL");
        String userId = currentUser.id();
        Post post = posts.create(userId, request.content(), null);
        inAppNotifications.onPostCreated(userId, post.id());
        pushNotifications.onPostCreated(userId, post.id());
        return post;
    }

    public Post createWithImage(String content, List<FileUpload> files) {
        if (content == null || content.isBlank() || content.length() > 2000)
            throw new BadRequestException("Content is required and must not exceed 2000 characters");
        if (files == null || files.size() != 1 || !"image".equals(files.get(0).name()))
            throw new BadRequestException("Exactly one image is required");
        MediaAsset image = mediaStorage.upload(files.get(0), "posts");
        try {
            Post post = posts.create(currentUser.id(), content.trim(), image);
            inAppNotifications.onPostCreated(currentUser.id(), post.id());
            pushNotifications.onPostCreated(currentUser.id(), post.id());
            return post;
        } catch (RuntimeException ex) {
            mediaCleanup.deleteOrQueue(image.key());
            throw ex;
        }
    }

    public Optional<Post> findById(String postId) {
        Optional<Post> post = posts.findById(postId, currentUser.id());
        if (post.isPresent()) requirePostVisible(postId);
        return post;
    }

    public String mediaUrl(String postId) {
        Post post = requirePostVisible(postId);
        if (post.mediaKey() == null || post.mediaKey().isBlank())
            throw new NotFoundException("Post has no image");
        return mediaStorage.publicUrl(post.mediaKey());
    }

    public void delete(String postId) {
        for (String mediaKey : posts.delete(postId, currentUser.id())) mediaCleanup.deleteQueued(mediaKey);
    }

    public void like(String postId) {
        requireInteractionPermission(postId);
        String userId = currentUser.id();
        boolean changed = posts.like(userId, postId);
        posts.findById(postId, userId).ifPresent(post -> {
            if (changed) feedDelivery.publishLikeChanged(postId, userId, post.likeCount(), true);
            if (post.authorId() != null) {
                inAppNotifications.onPostLiked(userId, postId, post.authorId());
                if (changed) pushNotifications.onPostLiked(userId, postId, post.authorId());
            }
        });
    }

    public void unlike(String postId) {
        requireInteractionPermission(postId);
        String userId = currentUser.id();
        if (posts.unlike(userId, postId))
            posts.findById(postId, userId).ifPresent(post ->
                    feedDelivery.publishLikeChanged(postId, userId, post.likeCount(), false));
    }

    public List<Post.Comment> getComments(String postId) {
        requirePostVisible(postId);
        posts.findById(postId, currentUser.id())
                .orElseThrow(() -> new NotFoundException("Post not found: " + postId));
        return posts.findComments(postId, currentUser.id());
    }

    public Post.Comment addComment(String postId, CreateCommentRequest request) {
        requireInteractionPermission(postId);
        String userId = currentUser.id();
        Post.Comment comment = posts.addComment(postId, userId, request.text().trim(), null);
        publishComment(postId, userId, comment);
        return comment;
    }

    public Post.Comment addCommentWithImage(String postId, String text, List<FileUpload> files) {
        requireInteractionPermission(postId);
        String content = text == null ? "" : text.trim();
        if (content.length() > 500) throw new BadRequestException("Comment cannot exceed 500 characters");
        if (files == null || files.size() != 1 || !"image".equals(files.get(0).name()))
            throw new BadRequestException("Exactly one image is required");
        posts.findById(postId, currentUser.id())
                .orElseThrow(() -> new NotFoundException("Post not found: " + postId));
        MediaAsset image = mediaStorage.upload(files.get(0), "comments");
        String userId = currentUser.id();
        Post.Comment comment;
        try {
            comment = posts.addComment(postId, userId, content, image);
        } catch (RuntimeException ex) {
            mediaCleanup.deleteOrQueue(image.key());
            throw ex;
        }
        publishComment(postId, userId, comment);
        return comment;
    }

    public String commentMediaUrl(String postId, String commentId) {
        requirePostVisible(postId);
        Post.Comment comment = posts.findComment(postId, commentId, currentUser.id());
        if (comment.mediaKey().isBlank()) throw new NotFoundException("Comment has no image");
        return mediaStorage.publicUrl(comment.mediaKey());
    }

    public Post.Comment reactToComment(String postId, String commentId, CommentReactionRequest request) {
        requireInteractionPermission(postId);
        if (request == null || !COMMENT_EMOJIS.contains(request.emoji()))
            throw new BadRequestException("Unsupported comment reaction");
        String userId = currentUser.id();
        posts.setCommentReaction(postId, commentId, userId, request.emoji());
        Post.Comment comment = posts.findComment(postId, commentId, userId);
        feedDelivery.publishCommentReactionChanged(postId, commentId, userId, comment.reactions(), request.emoji());
        return comment;
    }

    public Post.Comment removeCommentReaction(String postId, String commentId) {
        requireInteractionPermission(postId);
        String userId = currentUser.id();
        posts.removeCommentReaction(postId, commentId, userId);
        Post.Comment comment = posts.findComment(postId, commentId, userId);
        feedDelivery.publishCommentReactionChanged(postId, commentId, userId, comment.reactions(), "");
        return comment;
    }

    private void publishComment(String postId, String userId, Post.Comment comment) {
        posts.findById(postId, userId).ifPresent(post -> {
            feedDelivery.publishCommentCreated(postId, comment, post.commentCount());
            inAppNotifications.onPostCommented(userId, postId, comment.id(), post.authorId());
        });
    }
}
