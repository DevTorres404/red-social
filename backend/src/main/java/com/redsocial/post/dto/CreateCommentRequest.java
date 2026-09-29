package com.redsocial.post.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateCommentRequest(
        @NotBlank(message = "Comment text is required")
        @Size(max = 500, message = "Comment cannot exceed 500 characters")
        String text
) {}
