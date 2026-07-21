package com.ecommerce.shop.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @Schema(example = "customer1@example.com")
        @NotBlank @Email String email,

        @Schema(description = "At least 8 characters", example = "password123")
        @NotBlank @Size(min = 8, message = "password must be at least 8 characters") String password,

        @Schema(description = "Must be CUSTOMER or SELLER - ADMIN is not self-service", example = "CUSTOMER",
                allowableValues = {"CUSTOMER", "SELLER"})
        @NotBlank @Pattern(regexp = "CUSTOMER|SELLER", message = "role must be CUSTOMER or SELLER") String role
) {
}
