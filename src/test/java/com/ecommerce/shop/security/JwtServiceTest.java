package com.ecommerce.shop.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private final JwtProperties properties = new JwtProperties(
            "unit-test-secret-key-that-is-at-least-32-bytes-long",
            900_000L,
            604_800_000L
    );
    private final JwtService jwtService = new JwtService(properties);

    @Test
    void generatesAccessTokenWithExpectedClaims() {
        String token = jwtService.generateAccessToken("user-1", "a@b.com", "CUSTOMER");

        var claims = jwtService.parseAccessToken(token);

        assertThat(claims.getSubject()).isEqualTo("user-1");
        assertThat(claims.get("email", String.class)).isEqualTo("a@b.com");
        assertThat(claims.get("role", String.class)).isEqualTo("CUSTOMER");
    }

    @Test
    void generatesRefreshTokenWithUniqueJti() {
        var first = jwtService.generateRefreshToken("user-1");
        var second = jwtService.generateRefreshToken("user-1");

        assertThat(first.jti()).isNotEqualTo(second.jti());

        var claims = jwtService.parseRefreshToken(first.token());
        assertThat(claims.getSubject()).isEqualTo("user-1");
        assertThat(claims.getId()).isEqualTo(first.jti());
    }

    @Test
    void rejectsAccessTokenPresentedAsRefreshToken() {
        String accessToken = jwtService.generateAccessToken("user-1", "a@b.com", "CUSTOMER");

        assertThatThrownBy(() -> jwtService.parseRefreshToken(accessToken))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void rejectsRefreshTokenPresentedAsAccessToken() {
        var refreshToken = jwtService.generateRefreshToken("user-1");

        assertThatThrownBy(() -> jwtService.parseAccessToken(refreshToken.token()))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void rejectsTokenSignedWithDifferentKey() {
        JwtProperties otherProperties = new JwtProperties(
                "a-completely-different-unit-test-secret-key-32b",
                900_000L,
                604_800_000L
        );
        JwtService otherJwtService = new JwtService(otherProperties);
        String token = otherJwtService.generateAccessToken("user-1", "a@b.com", "CUSTOMER");

        assertThatThrownBy(() -> jwtService.parseAccessToken(token))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void rejectsExpiredToken() {
        JwtProperties expiredProperties = new JwtProperties(
                "unit-test-secret-key-that-is-at-least-32-bytes-long",
                -1_000L,
                604_800_000L
        );
        JwtService expiredJwtService = new JwtService(expiredProperties);
        String token = expiredJwtService.generateAccessToken("user-1", "a@b.com", "CUSTOMER");

        assertThatThrownBy(() -> jwtService.parseAccessToken(token))
                .isInstanceOf(InvalidTokenException.class);
    }
}
