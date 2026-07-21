package com.ecommerce.shop.auth.dto;

public record AuthResponse(String accessToken, String refreshToken, String tokenType, long expiresInMs) {
}
