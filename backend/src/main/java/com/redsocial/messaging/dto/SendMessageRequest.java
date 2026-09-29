package com.redsocial.messaging.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record SendMessageRequest(
        @NotBlank(message = "Recipient ID is required")
        String recipientId,

        @NotBlank(message = "Message text is required")
        @Size(max = 1000, message = "Message cannot exceed 1000 characters")
        String text
) {}
