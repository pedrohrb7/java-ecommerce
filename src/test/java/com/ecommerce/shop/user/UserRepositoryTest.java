package com.ecommerce.shop.user;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.mongodb.test.autoconfigure.DataMongoTest;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataMongoTest
class UserRepositoryTest {

    @Autowired
    private UserRepository userRepository;

    @AfterEach
    void cleanUp() {
        userRepository.deleteAll();
    }

    @Test
    void savesAndFindsUserByEmail() {
        User user = User.builder()
                .email("customer@example.com")
                .password("hashed-password")
                .role(Role.CUSTOMER)
                .createdAt(Instant.now())
                .build();
        userRepository.save(user);

        Optional<User> found = userRepository.findByEmail("customer@example.com");

        assertThat(found).isPresent();
        assertThat(found.get().getRole()).isEqualTo(Role.CUSTOMER);
    }

    @Test
    void existsByEmailReturnsFalseWhenNoUser() {
        assertThat(userRepository.existsByEmail("nobody@example.com")).isFalse();
    }

    @Test
    void existsByEmailReturnsTrueAfterSave() {
        User user = User.builder()
                .email("seller@example.com")
                .password("hashed-password")
                .role(Role.SELLER)
                .createdAt(Instant.now())
                .build();
        userRepository.save(user);

        assertThat(userRepository.existsByEmail("seller@example.com")).isTrue();
    }
}
