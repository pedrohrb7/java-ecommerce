package com.ecommerce.shop.demo;

import io.swagger.v3.oas.annotations.media.Schema;

public record MeResponse(
        String id,
        String email,
        @Schema(example = "CUSTOMER") String role
) {
}
