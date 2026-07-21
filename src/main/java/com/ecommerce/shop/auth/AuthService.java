package com.ecommerce.shop.auth;

import com.ecommerce.shop.auth.dto.RegisterRequest;
import com.ecommerce.shop.auth.dto.UserResponse;
import com.ecommerce.shop.user.Role;
import com.ecommerce.shop.user.User;
import com.ecommerce.shop.user.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
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
}
