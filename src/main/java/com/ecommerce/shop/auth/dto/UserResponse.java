package com.ecommerce.shop.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record UserResponse(
        String id,
        String email,
        @Schema(example = "CUSTOMER") String role
) {
}
