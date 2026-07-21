package com.ecommerce.shop.auth;

import com.ecommerce.shop.auth.dto.AuthResponse;
import com.ecommerce.shop.auth.dto.LoginRequest;
import com.ecommerce.shop.auth.dto.RefreshRequest;
import com.ecommerce.shop.auth.dto.RegisterRequest;
import com.ecommerce.shop.auth.dto.UserResponse;
import com.ecommerce.shop.security.InvalidTokenException;
import com.ecommerce.shop.security.JwtService;
import com.ecommerce.shop.security.UserPrincipal;
import com.ecommerce.shop.user.Role;
import com.ecommerce.shop.user.User;
import com.ecommerce.shop.user.UserRepository;
import io.jsonwebtoken.Claims;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;

    public AuthService(UserRepository userRepository,
                        RefreshTokenRepository refreshTokenRepository,
                        PasswordEncoder passwordEncoder,
                        AuthenticationManager authenticationManager,
                        JwtService jwtService) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
    }

    public UserResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new DuplicateEmailException(request.email());
        }

        User user = User.builder()
                .email(request.email())
                .password(passwordEncoder.encode(request.password()))
                .role(Role.valueOf(request.role()))
                .createdAt(Instant.now())
                .build();

        User saved = userRepository.save(user);

        return new UserResponse(saved.getId(), saved.getEmail(), saved.getRole().name());
    }

    public AuthResponse login(LoginRequest request) {
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.email(), request.password())
        );
        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();

        return issueTokenPair(principal.getId(), principal.getUsername(), principal.getRole());
    }

    public AuthResponse refresh(RefreshRequest request) {
        Claims claims = jwtService.parseRefreshToken(request.refreshToken());
        String jti = claims.getId();
        String userId = claims.getSubject();

        RefreshToken stored = refreshTokenRepository.findByJti(jti)
                .orElseThrow(() -> new InvalidTokenException("Refresh token not recognized"));

        if (stored.isRevoked() || stored.getExpiresAt().isBefore(Instant.now())) {
            throw new InvalidTokenException("Refresh token is no longer valid");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new InvalidTokenException("User no longer exists"));

        revoke(stored);

        return issueTokenPair(user.getId(), user.getEmail(), user.getRole().name());
    }

    public void logout(RefreshRequest request) {
        Claims claims = jwtService.parseRefreshToken(request.refreshToken());
        String jti = claims.getId();

        refreshTokenRepository.findByJti(jti).ifPresent(this::revoke);
    }

    private void revoke(RefreshToken token) {
        refreshTokenRepository.save(RefreshToken.builder()
                .id(token.getId())
                .userId(token.getUserId())
                .jti(token.getJti())
                .expiresAt(token.getExpiresAt())
                .revoked(true)
                .createdAt(token.getCreatedAt())
                .build());
    }

    private AuthResponse issueTokenPair(String userId, String email, String role) {
        String accessToken = jwtService.generateAccessToken(userId, email, role);
        JwtService.GeneratedRefreshToken refreshToken = jwtService.generateRefreshToken(userId);

        refreshTokenRepository.save(RefreshToken.builder()
                .userId(userId)
                .jti(refreshToken.jti())
                .expiresAt(refreshToken.expiresAt())
                .revoked(false)
                .createdAt(Instant.now())
                .build());

        return new AuthResponse(accessToken, refreshToken.token(), "Bearer", jwtService.getAccessTokenExpirationMs());
    }
}
