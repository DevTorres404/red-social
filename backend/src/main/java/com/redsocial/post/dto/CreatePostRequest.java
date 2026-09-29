package com.redsocial.post.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreatePostRequest(
        @NotBlank(message = "Content is required")
        @Size(max = 2000, message = "Post cannot exceed 2000 characters")
        String content,

        String mediaUrl
) {}
