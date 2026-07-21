package com.ecommerce.shop.security;

public record AuthenticatedUser(String id, String email, String role) {
}
