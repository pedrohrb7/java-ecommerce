package com.ecommerce.shop.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

public record RefreshRequest(
        @Schema(description = "Refresh token previously issued by /api/auth/login or /api/auth/refresh")
        @NotBlank String refreshToken
) {
}
