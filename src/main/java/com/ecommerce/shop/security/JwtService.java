package com.ecommerce.shop.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

@Service
public class JwtService {

    private static final String CLAIM_TYPE = "type";
    private static final String CLAIM_EMAIL = "email";
    private static final String CLAIM_ROLE = "role";
    private static final String TYPE_ACCESS = "access";
    private static final String TYPE_REFRESH = "refresh";

    private final SecretKey key;
    private final long accessTokenExpirationMs;
    private final long refreshTokenExpirationMs;

    public JwtService(JwtProperties properties) {
        this.key = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
        this.accessTokenExpirationMs = properties.accessTokenExpirationMs();
        this.refreshTokenExpirationMs = properties.refreshTokenExpirationMs();
    }

    public long getAccessTokenExpirationMs() {
        return accessTokenExpirationMs;
    }

    public String generateAccessToken(String userId, String email, String role) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId)
                .claim(CLAIM_EMAIL, email)
                .claim(CLAIM_ROLE, role)
                .claim(CLAIM_TYPE, TYPE_ACCESS)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(accessTokenExpirationMs)))
                .signWith(key)
                .compact();
    }

    public GeneratedRefreshToken generateRefreshToken(String userId) {
        Instant now = Instant.now();
        Instant expiresAt = now.plusMillis(refreshTokenExpirationMs);
        String jti = UUID.randomUUID().toString();
        String token = Jwts.builder()
                .subject(userId)
                .id(jti)
                .claim(CLAIM_TYPE, TYPE_REFRESH)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(key)
                .compact();
        return new GeneratedRefreshToken(token, jti, expiresAt);
    }

    public Claims parseAccessToken(String token) {
        return parseAndValidateType(token, TYPE_ACCESS);
    }

    public Claims parseRefreshToken(String token) {
        return parseAndValidateType(token, TYPE_REFRESH);
    }

    private Claims parseAndValidateType(String token, String expectedType) {
        Claims claims;
        try {
            claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (JwtException | IllegalArgumentException e) {
            throw new InvalidTokenException("Invalid or expired token");
        }
        if (!expectedType.equals(claims.get(CLAIM_TYPE, String.class))) {
            throw new InvalidTokenException("Unexpected token type");
        }
        return claims;
    }

    public record GeneratedRefreshToken(String token, String jti, Instant expiresAt) {
    }
}
