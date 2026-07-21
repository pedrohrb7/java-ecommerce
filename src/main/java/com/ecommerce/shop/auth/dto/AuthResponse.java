package com.ecommerce.shop.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record AuthResponse(
        @Schema(description = "Short-lived JWT to send as 'Authorization: Bearer <accessToken>'") String accessToken,
        @Schema(description = "Long-lived token used with /api/auth/refresh to obtain a new pair") String refreshToken,
        @Schema(example = "Bearer") String tokenType,
        @Schema(description = "Access token lifetime in milliseconds from issuance", example = "900000") long expiresInMs
) {
}
