package com.ecommerce.shop.auth;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.mongodb.test.autoconfigure.DataMongoTest;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataMongoTest
class RefreshTokenRepositoryTest {

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @AfterEach
    void cleanUp() {
        refreshTokenRepository.deleteAll();
    }

    @Test
    void savesAndFindsByJti() {
        RefreshToken refreshToken = RefreshToken.builder()
                .userId("user-1")
                .jti("jti-123")
                .expiresAt(Instant.now().plusSeconds(3600))
                .revoked(false)
                .createdAt(Instant.now())
                .build();
        refreshTokenRepository.save(refreshToken);

        Optional<RefreshToken> found = refreshTokenRepository.findByJti("jti-123");

        assertThat(found).isPresent();
        assertThat(found.get().getUserId()).isEqualTo("user-1");
        assertThat(found.get().isRevoked()).isFalse();
    }

    @Test
    void findByJtiReturnsEmptyWhenMissing() {
        assertThat(refreshTokenRepository.findByJti("missing")).isEmpty();
    }
}
