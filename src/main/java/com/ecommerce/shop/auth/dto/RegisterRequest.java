package com.ecommerce.shop.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank @Email String email,
        @NotBlank @Size(min = 8, message = "password must be at least 8 characters") String password,
        @NotBlank @Pattern(regexp = "CUSTOMER|SELLER", message = "role must be CUSTOMER or SELLER") String role
) {
}
