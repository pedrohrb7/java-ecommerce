# JWT Authentication & Feature-Based Folder Structure Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add JWT-based authentication (register/login/refresh/logout, role-based authorization for CUSTOMER/SELLER/ADMIN) to the `shop` API, organized as a feature-based package structure, per `docs/specs/2026-07-20-jwt-auth-and-structure-design.md`.

**Architecture:** Feature-based packages under `com.ecommerce.shop` (`user/`, `auth/`, `security/`, `demo/`, `common/exception/`). A manually-written `JwtAuthenticationFilter` validates access tokens and builds `Authentication` directly from token claims (no DB hit per request). Refresh tokens are tracked in MongoDB by their `jti` claim so they can be revoked and rotated.

**Tech Stack:** Spring Boot 4.1.0, Spring Security 7.1.0 (via `spring-boot-starter-security`), `io.jsonwebtoken` (jjwt) 0.12.6, Spring Data MongoDB, Lombok, Jakarta Bean Validation, JUnit 5 (Jupiter 6.0.3) + AssertJ + MockMvc.

## Global Constraints

- Java 21, Spring Boot 4.1.0 (parent-managed versions — do not hardcode versions Boot already manages; only jjwt needs an explicit version, `0.12.6`).
- Package root: `com.ecommerce.shop`. Feature-based packages only — do not introduce top-level `controller/`, `service/`, `repository/` packages.
- Roles: `CUSTOMER`, `SELLER`, `ADMIN` as a single `Role` enum field on `User` (not a set). `ADMIN` is never selectable via `/api/auth/register`.
- Access token expiry: 15 minutes (`900000` ms). Refresh token expiry: 7 days (`604800000` ms). Both configurable via `jwt.access-token-expiration-ms` / `jwt.refresh-token-expiration-ms`.
- Every JWT carries a `type` claim (`"access"` or `"refresh"`) and must be checked on every validation.
- Refresh tokens are tracked in MongoDB (`refresh_tokens` collection) by their `jti` claim, not by the raw token string. Every `/api/auth/refresh` call rotates: old `jti` is deleted, a new access+refresh pair is issued.
- Passwords hashed with `BCryptPasswordEncoder`. Never log or return a password.
- `spring.data.mongodb.uri` resolves to `mongodb://localhost:27017/shop` for the running app and `mongodb://localhost:27017/shop_test` for tests. Both require MongoDB reachable at `localhost:27017` — this repo provisions that via `docker compose up -d mongodb` (Task 1). **Every task from Task 2 onward requires that container running before its tests will pass.**
- `JwtAuthenticationFilter` must catch token validation failures itself and simply leave the security context empty — it must never let an exception propagate out of the filter chain, because filters run before the `DispatcherServlet` and `@RestControllerAdvice` cannot catch what they throw.
- `AuthService.login()` and any code touching Spring Security's `AuthenticationManager` must not distinguish "wrong password" from "unknown email" in the response — both surface as `BadCredentialsException` → generic 401 "Invalid email or password" (Spring Security's `DaoAuthenticationProvider` already does this by default via `hideUserNotFoundExceptions`).

---

### Task 1: Local MongoDB via Docker Compose, Dockerfile, and new dependencies

**Files:**
- Create: `Dockerfile`
- Create: `docker-compose.yml`
- Create: `.dockerignore`
- Modify: `pom.xml`
- Modify: `src/main/resources/application.properties`
- Create: `src/test/resources/application.properties`

**Interfaces:**
- Produces: a reachable MongoDB at `localhost:27017` (via `docker compose up -d mongodb`), `spring.data.mongodb.uri`, `jwt.secret`, `jwt.access-token-expiration-ms`, `jwt.refresh-token-expiration-ms` properties available to every later task. `pom.xml` gains `spring-boot-starter-security`, `spring-boot-starter-validation`, and `io.jsonwebtoken:jjwt-{api,impl,jackson}:0.12.6`.

- [x] **Step 1: Create the Dockerfile**

```dockerfile
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -q dependency:go-offline
COPY src ./src
RUN ./mvnw -q package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

- [x] **Step 2: Create `.dockerignore`**

```
target
.git
docs
*.md
.settings
.classpath
.project
.factorypath
```

- [x] **Step 3: Create `docker-compose.yml`**

```yaml
services:
  mongodb:
    image: mongo:7.0
    container_name: shop-mongodb
    ports:
      - "27017:27017"
    volumes:
      - mongo_data:/data/db

  app:
    build: .
    container_name: shop-app
    depends_on:
      - mongodb
    ports:
      - "8080:8080"
    environment:
      SPRING_DATA_MONGODB_URI: mongodb://mongodb:27017/shop

volumes:
  mongo_data:
```

- [x] **Step 4: Add new dependencies to `pom.xml`**

Insert these five `<dependency>` blocks into the existing `<dependencies>` section (anywhere among the other entries is fine — order doesn't matter to Maven):

```xml
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-security</artifactId>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-validation</artifactId>
		</dependency>
		<dependency>
			<groupId>io.jsonwebtoken</groupId>
			<artifactId>jjwt-api</artifactId>
			<version>0.12.6</version>
		</dependency>
		<dependency>
			<groupId>io.jsonwebtoken</groupId>
			<artifactId>jjwt-impl</artifactId>
			<version>0.12.6</version>
			<scope>runtime</scope>
		</dependency>
		<dependency>
			<groupId>io.jsonwebtoken</groupId>
			<artifactId>jjwt-jackson</artifactId>
			<version>0.12.6</version>
			<scope>runtime</scope>
		</dependency>
```

Run: `./mvnw -q dependency:tree`
Expected: `BUILD SUCCESS`, tree includes `spring-boot-starter-security`, `spring-boot-starter-validation`, and the three `jjwt-*:0.12.6` entries.

- [x] **Step 5: Update `src/main/resources/application.properties`**

Replace the file's contents with:

```properties
spring.application.name=shop
spring.data.mongodb.uri=${SPRING_DATA_MONGODB_URI:mongodb://localhost:27017/shop}

jwt.secret=${JWT_SECRET:dev-only-secret-change-me-please-32bytes-min}
jwt.access-token-expiration-ms=900000
jwt.refresh-token-expiration-ms=604800000
```

- [x] **Step 6: Create `src/test/resources/application.properties`**

Test resources take precedence over main resources on the test classpath, so this overrides the above automatically for every test — no `@ActiveProfiles` needed.

```properties
spring.application.name=shop
spring.data.mongodb.uri=mongodb://localhost:27017/shop_test

jwt.secret=test-only-secret-do-not-use-in-prod-32bytes-min
jwt.access-token-expiration-ms=900000
jwt.refresh-token-expiration-ms=604800000
```

- [x] **Step 7: Start MongoDB and verify the app still boots and connects**

Run: `docker compose up -d mongodb`
Expected: container `shop-mongodb` running (`docker compose ps` shows it healthy/up).

Run: `./mvnw test -Dtest=ShopApplicationTests`
Expected: `BUILD SUCCESS`, `Tests run: 1, Failures: 0, Errors: 0` (this is the existing `contextLoads` test — it now proves the app starts cleanly with Security/Validation/jjwt on the classpath and a real Mongo reachable).

- [x] **Step 8: Commit**

```bash
git add Dockerfile docker-compose.yml .dockerignore pom.xml src/main/resources/application.properties src/test/resources/application.properties
git commit -m "chore: add Docker Compose MongoDB, security/jwt dependencies, and jwt config"
```

---

### Task 2: User domain — Role, User, UserRepository

**Files:**
- Create: `src/main/java/com/ecommerce/shop/user/Role.java`
- Create: `src/main/java/com/ecommerce/shop/user/User.java`
- Create: `src/main/java/com/ecommerce/shop/user/UserRepository.java`
- Test: `src/test/java/com/ecommerce/shop/user/UserRepositoryTest.java`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `Role` enum (`CUSTOMER`, `SELLER`, `ADMIN`); `User` (Lombok `@Getter @NoArgsConstructor @AllArgsConstructor @Builder`, fields `id`, `email`, `password`, `role`, `createdAt`); `UserRepository extends MongoRepository<User, String>` with `Optional<User> findByEmail(String)` and `boolean existsByEmail(String)` — both consumed by `CustomUserDetailsService` (Task 4) and `AuthService` (Task 7+).

- [x] **Step 1: Write the failing test**

```java
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
```

- [x] **Step 2: Run test to verify it fails**

Precondition: `docker compose up -d mongodb` (see Task 1).

Run: `./mvnw test -Dtest=UserRepositoryTest`
Expected: FAIL to compile — `Role`, `User`, `UserRepository` don't exist yet.

- [x] **Step 3: Write `Role.java`**

```java
package com.ecommerce.shop.user;

public enum Role {
    CUSTOMER,
    SELLER,
    ADMIN
}
```

- [x] **Step 4: Write `User.java`**

```java
package com.ecommerce.shop.user;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document(collection = "users")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    @Id
    private String id;

    @Indexed(unique = true)
    private String email;

    private String password;

    private Role role;

    private Instant createdAt;
}
```

- [x] **Step 5: Write `UserRepository.java`**

```java
package com.ecommerce.shop.user;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface UserRepository extends MongoRepository<User, String> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);
}
```

- [x] **Step 6: Run test to verify it passes**

Run: `./mvnw test -Dtest=UserRepositoryTest`
Expected: `Tests run: 3, Failures: 0, Errors: 0`.

- [x] **Step 7: Commit**

```bash
git add src/main/java/com/ecommerce/shop/user src/test/java/com/ecommerce/shop/user
git commit -m "feat: add User domain model and repository"
```

---

### Task 3: JWT foundations — exceptions, JwtProperties, JwtService

**Files:**
- Create: `src/main/java/com/ecommerce/shop/common/exception/ApiException.java`
- Create: `src/main/java/com/ecommerce/shop/common/exception/ApiError.java`
- Create: `src/main/java/com/ecommerce/shop/security/JwtProperties.java`
- Create: `src/main/java/com/ecommerce/shop/security/InvalidTokenException.java`
- Create: `src/main/java/com/ecommerce/shop/security/JwtService.java`
- Modify: `src/main/java/com/ecommerce/shop/ShopApplication.java`
- Test: `src/test/java/com/ecommerce/shop/security/JwtServiceTest.java`

**Interfaces:**
- Consumes: nothing from earlier tasks (this is a pure unit test, no Mongo, no Spring context).
- Produces: `ApiException` (abstract, `getStatus(): HttpStatus`) — base for all later domain exceptions; `ApiError` record (`timestamp, status, error, message, path`); `JwtService` with `generateAccessToken(String userId, String email, String role): String`, `generateRefreshToken(String userId): JwtService.GeneratedRefreshToken` (`record GeneratedRefreshToken(String token, String jti, Instant expiresAt)`), `parseAccessToken(String): Claims`, `parseRefreshToken(String): Claims`, `getAccessTokenExpirationMs(): long` — all consumed by `JwtAuthenticationFilter` (Task 5) and `AuthService` (Task 7+).

- [x] **Step 1: Write the failing test**

```java
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
```

- [x] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=JwtServiceTest`
Expected: FAIL to compile — none of `JwtProperties`, `JwtService`, `InvalidTokenException` exist yet.

- [x] **Step 3: Write `ApiException.java`**

```java
package com.ecommerce.shop.common.exception;

import org.springframework.http.HttpStatus;

public abstract class ApiException extends RuntimeException {

    private final HttpStatus status;

    protected ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
```

- [x] **Step 4: Write `ApiError.java`**

```java
package com.ecommerce.shop.common.exception;

import java.time.Instant;

public record ApiError(Instant timestamp, int status, String error, String message, String path) {
}
```

- [x] **Step 5: Write `JwtProperties.java`**

```java
package com.ecommerce.shop.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "jwt")
public record JwtProperties(String secret, long accessTokenExpirationMs, long refreshTokenExpirationMs) {
}
```

- [x] **Step 6: Write `InvalidTokenException.java`**

```java
package com.ecommerce.shop.security;

import com.ecommerce.shop.common.exception.ApiException;
import org.springframework.http.HttpStatus;

public class InvalidTokenException extends ApiException {
    public InvalidTokenException(String message) {
        super(HttpStatus.UNAUTHORIZED, message);
    }
}
```

- [x] **Step 7: Write `JwtService.java`**

```java
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
```

- [x] **Step 8: Enable `@ConfigurationProperties` binding — modify `ShopApplication.java`**

```java
package com.ecommerce.shop;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ShopApplication {

    public static void main(String[] args) {
        SpringApplication.run(ShopApplication.class, args);
    }

}
```

- [x] **Step 9: Run test to verify it passes**

Run: `./mvnw test -Dtest=JwtServiceTest`
Expected: `Tests run: 6, Failures: 0, Errors: 0`.

- [x] **Step 10: Commit**

```bash
git add src/main/java/com/ecommerce/shop/common src/main/java/com/ecommerce/shop/security src/main/java/com/ecommerce/shop/ShopApplication.java src/test/java/com/ecommerce/shop/security
git commit -m "feat: add JwtService, JwtProperties, and base API exception types"
```

---

### Task 4: UserPrincipal & CustomUserDetailsService

**Files:**
- Create: `src/main/java/com/ecommerce/shop/security/UserPrincipal.java`
- Create: `src/main/java/com/ecommerce/shop/security/CustomUserDetailsService.java`
- Test: `src/test/java/com/ecommerce/shop/security/CustomUserDetailsServiceTest.java`

**Interfaces:**
- Consumes: `UserRepository.findByEmail` (Task 2), `User` fields (Task 2).
- Produces: `UserPrincipal implements UserDetails` with `getId(): String`, `getRole(): String` (in addition to standard `UserDetails` methods) — consumed by `AuthService.login()` (Task 8) after `AuthenticationManager.authenticate(...)` returns it as `Authentication.getPrincipal()`. `CustomUserDetailsService implements UserDetailsService` — auto-detected by Spring Security's `AuthenticationConfiguration` and wired into `SecurityConfig`'s `DaoAuthenticationProvider` (Task 5).

- [x] **Step 1: Write the failing test**

```java
package com.ecommerce.shop.security;

import com.ecommerce.shop.user.Role;
import com.ecommerce.shop.user.User;
import com.ecommerce.shop.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.mongodb.test.autoconfigure.DataMongoTest;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataMongoTest
class CustomUserDetailsServiceTest {

    @Autowired
    private UserRepository userRepository;

    private CustomUserDetailsService customUserDetailsService;

    @BeforeEach
    void setUp() {
        customUserDetailsService = new CustomUserDetailsService(userRepository);
    }

    @AfterEach
    void cleanUp() {
        userRepository.deleteAll();
    }

    @Test
    void loadsUserByEmailWithRoleAuthority() {
        userRepository.save(User.builder()
                .email("admin@example.com")
                .password("hashed")
                .role(Role.ADMIN)
                .createdAt(Instant.now())
                .build());

        UserDetails userDetails = customUserDetailsService.loadUserByUsername("admin@example.com");

        assertThat(userDetails.getUsername()).isEqualTo("admin@example.com");
        assertThat(userDetails.getAuthorities())
                .extracting(Object::toString)
                .containsExactly("ROLE_ADMIN");
    }

    @Test
    void throwsWhenEmailNotFound() {
        assertThatThrownBy(() -> customUserDetailsService.loadUserByUsername("missing@example.com"))
                .isInstanceOf(UsernameNotFoundException.class);
    }
}
```

- [x] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=CustomUserDetailsServiceTest`
Expected: FAIL to compile — `UserPrincipal` and `CustomUserDetailsService` don't exist yet.

- [x] **Step 3: Write `UserPrincipal.java`**

```java
package com.ecommerce.shop.security;

import com.ecommerce.shop.user.User;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.List;

public class UserPrincipal implements UserDetails {

    private final String id;
    private final String email;
    private final String password;
    private final String role;

    public UserPrincipal(String id, String email, String password, String role) {
        this.id = id;
        this.email = email;
        this.password = password;
        this.role = role;
    }

    public static UserPrincipal from(User user) {
        return new UserPrincipal(user.getId(), user.getEmail(), user.getPassword(), user.getRole().name());
    }

    public String getId() {
        return id;
    }

    public String getRole() {
        return role;
    }

    @Override
    public List<GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role));
    }

    @Override
    public String getPassword() {
        return password;
    }

    @Override
    public String getUsername() {
        return email;
    }
}
```

- [x] **Step 4: Write `CustomUserDetailsService.java`**

```java
package com.ecommerce.shop.security;

import com.ecommerce.shop.user.UserRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    public CustomUserDetailsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String email) {
        return userRepository.findByEmail(email)
                .map(UserPrincipal::from)
                .orElseThrow(() -> new UsernameNotFoundException("No user with email " + email));
    }
}
```

- [x] **Step 5: Run test to verify it passes**

Run: `./mvnw test -Dtest=CustomUserDetailsServiceTest`
Expected: `Tests run: 2, Failures: 0, Errors: 0`.

- [x] **Step 6: Commit**

```bash
git add src/main/java/com/ecommerce/shop/security/UserPrincipal.java src/main/java/com/ecommerce/shop/security/CustomUserDetailsService.java src/test/java/com/ecommerce/shop/security/CustomUserDetailsServiceTest.java
git commit -m "feat: add UserPrincipal and CustomUserDetailsService"
```

---

### Task 5: Security filter chain, SecurityConfig & demo endpoints

**Files:**
- Create: `src/main/java/com/ecommerce/shop/security/AuthenticatedUser.java`
- Create: `src/main/java/com/ecommerce/shop/security/JwtAuthenticationFilter.java`
- Create: `src/main/java/com/ecommerce/shop/security/SecurityConfig.java`
- Create: `src/main/java/com/ecommerce/shop/demo/MeResponse.java`
- Create: `src/main/java/com/ecommerce/shop/demo/DemoController.java`
- Test: `src/test/java/com/ecommerce/shop/demo/DemoControllerIntegrationTest.java`

**Interfaces:**
- Consumes: `JwtService.parseAccessToken` (Task 3), `CustomUserDetailsService`/`UserPrincipal` (Task 4, auto-detected by Spring Security — not called directly by this task's code).
- Produces: `AuthenticatedUser` record (`id, email, role`) set as the request-scoped `Authentication` principal for every authenticated request — this is what any future protected controller reads via `@AuthenticationPrincipal AuthenticatedUser user`. `SecurityConfig` locks in the authorization rules `/api/auth/**` → `permitAll`, `/api/admin/**` → `hasRole("ADMIN")`, else → `authenticated`.

- [x] **Step 1: Write the failing test**

```java
package com.ecommerce.shop.demo;

import com.ecommerce.shop.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class DemoControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Test
    void meWithoutTokenReturns401() throws Exception {
        mockMvc.perform(get("/api/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void meWithValidTokenReturnsCallerInfo() throws Exception {
        String token = jwtService.generateAccessToken("user-1", "customer@example.com", "CUSTOMER");

        mockMvc.perform(get("/api/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("customer@example.com"))
                .andExpect(jsonPath("$.role").value("CUSTOMER"));
    }

    @Test
    void adminPingWithCustomerTokenReturns403() throws Exception {
        String token = jwtService.generateAccessToken("user-1", "customer@example.com", "CUSTOMER");

        mockMvc.perform(get("/api/admin/ping").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminPingWithAdminTokenReturns200() throws Exception {
        String token = jwtService.generateAccessToken("user-1", "admin@example.com", "ADMIN");

        mockMvc.perform(get("/api/admin/ping").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }
}
```

- [x] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=DemoControllerIntegrationTest`
Expected: FAIL to compile — `DemoController` doesn't exist yet (and without a `SecurityConfig`, Spring Boot's default security auto-configuration would lock down every endpoint behind a generated password anyway).

- [x] **Step 3: Write `AuthenticatedUser.java`**

```java
package com.ecommerce.shop.security;

public record AuthenticatedUser(String id, String email, String role) {
}
```

- [x] **Step 4: Write `JwtAuthenticationFilter.java`**

```java
package com.ecommerce.shop.security;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String AUTH_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader(AUTH_HEADER);

        if (header != null && header.startsWith(BEARER_PREFIX)) {
            String token = header.substring(BEARER_PREFIX.length());
            try {
                Claims claims = jwtService.parseAccessToken(token);
                AuthenticatedUser principal = new AuthenticatedUser(
                        claims.getSubject(),
                        claims.get("email", String.class),
                        claims.get("role", String.class)
                );
                List<SimpleGrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_" + principal.role()));
                var authentication = new UsernamePasswordAuthenticationToken(principal, null, authorities);
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (InvalidTokenException e) {
                SecurityContextHolder.clearContext();
            }
        }

        filterChain.doFilter(request, response);
    }
}
```

Note the `catch (InvalidTokenException e)`: a filter runs before the `DispatcherServlet`, so `@RestControllerAdvice` cannot catch anything thrown here. An invalid/missing token must simply result in "no authentication set" — Spring Security's own entry point then returns 401 for endpoints that require `authenticated()`.

- [x] **Step 5: Write `SecurityConfig.java`**

```java
package com.ecommerce.shop.security;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public DaoAuthenticationProvider daoAuthenticationProvider(UserDetailsService userDetailsService,
                                                                 PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return provider;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(handling -> handling.authenticationEntryPoint(
                        (request, response, authException) -> response.sendError(HttpServletResponse.SC_UNAUTHORIZED)
                ))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/**").permitAll()
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated()
                )
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
```

> **Deviation from original plan (discovered during implementation):** without an explicit `httpBasic()`/`formLogin()` configured, Spring Security's default `AuthenticationEntryPoint` is `Http403ForbiddenEntryPoint`, so unauthenticated requests returned `403` instead of the `401` this task's test expects. Added the explicit `exceptionHandling(...)` block above to fix it — confirmed by `DemoControllerIntegrationTest.meWithoutTokenReturns401` failing (`403` actual) before the fix and passing after.

- [x] **Step 6: Write `MeResponse.java`**

```java
package com.ecommerce.shop.demo;

public record MeResponse(String id, String email, String role) {
}
```

- [x] **Step 7: Write `DemoController.java`**

```java
package com.ecommerce.shop.demo;

import com.ecommerce.shop.security.AuthenticatedUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DemoController {

    @GetMapping("/api/me")
    public MeResponse me(@AuthenticationPrincipal AuthenticatedUser user) {
        return new MeResponse(user.id(), user.email(), user.role());
    }

    @GetMapping("/api/admin/ping")
    public String adminPing() {
        return "pong";
    }
}
```

- [x] **Step 8: Run test to verify it passes**

Run: `./mvnw test -Dtest=DemoControllerIntegrationTest`
Expected: `Tests run: 4, Failures: 0, Errors: 0`.

- [x] **Step 9: Commit**

```bash
git add src/main/java/com/ecommerce/shop/security src/main/java/com/ecommerce/shop/demo src/test/java/com/ecommerce/shop/demo
git commit -m "feat: add JWT auth filter, SecurityConfig, and role-demo endpoints"
```

---

### Task 6: RefreshToken domain & repository

**Files:**
- Create: `src/main/java/com/ecommerce/shop/auth/RefreshToken.java`
- Create: `src/main/java/com/ecommerce/shop/auth/RefreshTokenRepository.java`
- Test: `src/test/java/com/ecommerce/shop/auth/RefreshTokenRepositoryTest.java`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `RefreshToken` (Lombok `@Getter @NoArgsConstructor @AllArgsConstructor @Builder`, fields `id, userId, jti, expiresAt, revoked, createdAt`); `RefreshTokenRepository extends MongoRepository<RefreshToken, String>` with `Optional<RefreshToken> findByJti(String)` — consumed by `AuthService` (Task 8+).

- [x] **Step 1: Write the failing test**

```java
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
```

- [x] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=RefreshTokenRepositoryTest`
Expected: FAIL to compile — `RefreshToken` and `RefreshTokenRepository` don't exist yet.

- [x] **Step 3: Write `RefreshToken.java`**

```java
package com.ecommerce.shop.auth;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document(collection = "refresh_tokens")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RefreshToken {

    @Id
    private String id;

    private String userId;

    @Indexed(unique = true)
    private String jti;

    private Instant expiresAt;

    private boolean revoked;

    private Instant createdAt;
}
```

- [x] **Step 4: Write `RefreshTokenRepository.java`**

```java
package com.ecommerce.shop.auth;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface RefreshTokenRepository extends MongoRepository<RefreshToken, String> {

    Optional<RefreshToken> findByJti(String jti);
}
```

- [x] **Step 5: Run test to verify it passes**

Run: `./mvnw test -Dtest=RefreshTokenRepositoryTest`
Expected: `Tests run: 2, Failures: 0, Errors: 0`.

- [x] **Step 6: Commit**

```bash
git add src/main/java/com/ecommerce/shop/auth src/test/java/com/ecommerce/shop/auth
git commit -m "feat: add RefreshToken domain model and repository"
```

---

### Task 7: Register endpoint

**Files:**
- Create: `src/main/java/com/ecommerce/shop/common/exception/GlobalExceptionHandler.java`
- Create: `src/main/java/com/ecommerce/shop/auth/DuplicateEmailException.java`
- Create: `src/main/java/com/ecommerce/shop/auth/dto/RegisterRequest.java`
- Create: `src/main/java/com/ecommerce/shop/auth/dto/UserResponse.java`
- Create: `src/main/java/com/ecommerce/shop/auth/AuthService.java`
- Create: `src/main/java/com/ecommerce/shop/auth/AuthController.java`
- Test: `src/test/java/com/ecommerce/shop/auth/AuthControllerRegisterTest.java`

**Interfaces:**
- Consumes: `UserRepository` (Task 2), `ApiException`/`ApiError` (Task 3).
- Produces: `GlobalExceptionHandler` (`@RestControllerAdvice`, maps `ApiException` → its own status, `BadCredentialsException` → 401, `MethodArgumentNotValidException` → 400, anything else → 500 — this is the handler every later `auth` endpoint relies on). `AuthService.register(RegisterRequest): UserResponse`. `AuthController` at `/api/auth` with `POST /register`.

- [x] **Step 1: Write the failing test**

```java
package com.ecommerce.shop.auth;

import com.ecommerce.shop.auth.dto.RegisterRequest;
import com.ecommerce.shop.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AuthControllerRegisterTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @AfterEach
    void cleanUp() {
        userRepository.deleteAll();
    }

    @Test
    void registersNewCustomer() throws Exception {
        String body = objectMapper.writeValueAsString(new RegisterRequest("customer@example.com", "password123", "CUSTOMER"));

        mockMvc.perform(post("/api/auth/register").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("customer@example.com"))
                .andExpect(jsonPath("$.role").value("CUSTOMER"));
    }

    @Test
    void rejectsDuplicateEmail() throws Exception {
        String body = objectMapper.writeValueAsString(new RegisterRequest("dup@example.com", "password123", "SELLER"));
        mockMvc.perform(post("/api/auth/register").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/auth/register").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
    }

    @Test
    void rejectsShortPassword() throws Exception {
        String body = objectMapper.writeValueAsString(new RegisterRequest("short@example.com", "abc", "CUSTOMER"));

        mockMvc.perform(post("/api/auth/register").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsAdminRoleOnSelfRegistration() throws Exception {
        String body = objectMapper.writeValueAsString(new RegisterRequest("wannabe-admin@example.com", "password123", "ADMIN"));

        mockMvc.perform(post("/api/auth/register").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }
}
```

- [x] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=AuthControllerRegisterTest`
Expected: FAIL to compile — `RegisterRequest`, `AuthService`, `AuthController` don't exist yet.

- [x] **Step 3: Write `GlobalExceptionHandler.java`**

```java
package com.ecommerce.shop.common.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApiException(ApiException ex, HttpServletRequest request) {
        return build(ex.getStatus(), ex.getMessage(), request);
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ApiError> handleBadCredentials(HttpServletRequest request) {
        return build(HttpStatus.UNAUTHORIZED, "Invalid email or password", request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return build(HttpStatus.BAD_REQUEST, message, request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest request) {
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error", request);
    }

    private ResponseEntity<ApiError> build(HttpStatus status, String message, HttpServletRequest request) {
        ApiError body = new ApiError(Instant.now(), status.value(), status.getReasonPhrase(), message, request.getRequestURI());
        return ResponseEntity.status(status).body(body);
    }
}
```

- [x] **Step 4: Write `DuplicateEmailException.java`**

```java
package com.ecommerce.shop.auth;

import com.ecommerce.shop.common.exception.ApiException;
import org.springframework.http.HttpStatus;

public class DuplicateEmailException extends ApiException {
    public DuplicateEmailException(String email) {
        super(HttpStatus.CONFLICT, "Email already registered: " + email);
    }
}
```

- [x] **Step 5: Write `RegisterRequest.java`**

```java
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
```

- [x] **Step 6: Write `UserResponse.java`**

```java
package com.ecommerce.shop.auth.dto;

public record UserResponse(String id, String email, String role) {
}
```

- [x] **Step 7: Write `AuthService.java`**

```java
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
```

- [x] **Step 8: Write `AuthController.java`**

```java
package com.ecommerce.shop.auth;

import com.ecommerce.shop.auth.dto.RegisterRequest;
import com.ecommerce.shop.auth.dto.UserResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public ResponseEntity<UserResponse> register(@Valid @RequestBody RegisterRequest request) {
        UserResponse response = authService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
```

- [x] **Step 9: Run test to verify it passes**

Run: `./mvnw test -Dtest=AuthControllerRegisterTest`
Expected: `Tests run: 4, Failures: 0, Errors: 0`.

- [x] **Step 10: Commit**

```bash
git add src/main/java/com/ecommerce/shop/common/exception/GlobalExceptionHandler.java src/main/java/com/ecommerce/shop/auth src/test/java/com/ecommerce/shop/auth/AuthControllerRegisterTest.java
git commit -m "feat: add register endpoint and global exception handling"
```

---

### Task 8: Login endpoint

**Files:**
- Create: `src/main/java/com/ecommerce/shop/auth/dto/LoginRequest.java`
- Create: `src/main/java/com/ecommerce/shop/auth/dto/AuthResponse.java`
- Modify: `src/main/java/com/ecommerce/shop/auth/AuthService.java`
- Modify: `src/main/java/com/ecommerce/shop/auth/AuthController.java`
- Test: `src/test/java/com/ecommerce/shop/auth/AuthControllerLoginTest.java`

**Interfaces:**
- Consumes: `AuthenticationManager` (Task 5), `UserPrincipal` (Task 4), `JwtService.generateAccessToken/generateRefreshToken/getAccessTokenExpirationMs` (Task 3), `RefreshTokenRepository` (Task 6).
- Produces: `AuthService.login(LoginRequest): AuthResponse`; a private `AuthService.issueTokenPair(String userId, String email, String role): AuthResponse` helper that Task 9's `refresh()` will reuse.

- [x] **Step 1: Write the failing test**

```java
package com.ecommerce.shop.auth;

import com.ecommerce.shop.auth.dto.RegisterRequest;
import com.ecommerce.shop.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AuthControllerLoginTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @BeforeEach
    void registerTestUser() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                .contentType(APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new RegisterRequest("login@example.com", "password123", "CUSTOMER"))));
    }

    @AfterEach
    void cleanUp() {
        userRepository.deleteAll();
    }

    @Test
    void loginWithCorrectCredentialsReturnsTokens() throws Exception {
        String body = objectMapper.writeValueAsString(new LoginBody("login@example.com", "password123"));

        mockMvc.perform(post("/api/auth/login").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"));
    }

    @Test
    void loginWithWrongPasswordReturns401() throws Exception {
        String body = objectMapper.writeValueAsString(new LoginBody("login@example.com", "wrong-password"));

        mockMvc.perform(post("/api/auth/login").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid email or password"));
    }

    @Test
    void loginWithUnknownEmailReturns401() throws Exception {
        String body = objectMapper.writeValueAsString(new LoginBody("nobody@example.com", "password123"));

        mockMvc.perform(post("/api/auth/login").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
    }

    private record LoginBody(String email, String password) {
    }
}
```

- [x] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=AuthControllerLoginTest`
Expected: FAIL to compile — no `/api/auth/login` mapping, no `LoginRequest`/`AuthResponse`.

- [x] **Step 3: Write `LoginRequest.java`**

```java
package com.ecommerce.shop.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
        @NotBlank @Email String email,
        @NotBlank String password
) {
}
```

- [x] **Step 4: Write `AuthResponse.java`**

```java
package com.ecommerce.shop.auth.dto;

public record AuthResponse(String accessToken, String refreshToken, String tokenType, long expiresInMs) {
}
```

- [x] **Step 5: Replace `AuthService.java` with the full updated version**

```java
package com.ecommerce.shop.auth;

import com.ecommerce.shop.auth.dto.AuthResponse;
import com.ecommerce.shop.auth.dto.LoginRequest;
import com.ecommerce.shop.auth.dto.RegisterRequest;
import com.ecommerce.shop.auth.dto.UserResponse;
import com.ecommerce.shop.security.JwtService;
import com.ecommerce.shop.security.UserPrincipal;
import com.ecommerce.shop.user.Role;
import com.ecommerce.shop.user.User;
import com.ecommerce.shop.user.UserRepository;
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
```

- [x] **Step 6: Replace `AuthController.java` with the full updated version**

```java
package com.ecommerce.shop.auth;

import com.ecommerce.shop.auth.dto.AuthResponse;
import com.ecommerce.shop.auth.dto.LoginRequest;
import com.ecommerce.shop.auth.dto.RegisterRequest;
import com.ecommerce.shop.auth.dto.UserResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public ResponseEntity<UserResponse> register(@Valid @RequestBody RegisterRequest request) {
        UserResponse response = authService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }
}
```

- [x] **Step 7: Run test to verify it passes**

Run: `./mvnw test -Dtest=AuthControllerLoginTest`
Expected: `Tests run: 3, Failures: 0, Errors: 0`.

Also re-run Task 7's test to confirm no regression: `./mvnw test -Dtest=AuthControllerRegisterTest` → `Tests run: 4, Failures: 0, Errors: 0`.

- [x] **Step 8: Commit**

```bash
git add src/main/java/com/ecommerce/shop/auth src/test/java/com/ecommerce/shop/auth/AuthControllerLoginTest.java
git commit -m "feat: add login endpoint issuing access and refresh tokens"
```

---

### Task 9: Refresh endpoint

**Files:**
- Create: `src/main/java/com/ecommerce/shop/auth/dto/RefreshRequest.java`
- Modify: `src/main/java/com/ecommerce/shop/auth/AuthService.java`
- Modify: `src/main/java/com/ecommerce/shop/auth/AuthController.java`
- Test: `src/test/java/com/ecommerce/shop/auth/AuthControllerRefreshTest.java`

**Interfaces:**
- Consumes: `JwtService.parseRefreshToken` (Task 3), `RefreshTokenRepository.findByJti` (Task 6), `InvalidTokenException` (Task 3), `AuthService.issueTokenPair` (Task 8, private helper reused here).
- Produces: `AuthService.refresh(RefreshRequest): AuthResponse` — rotates the refresh token (deletes the old `jti`, issues a new pair).

- [x] **Step 1: Write the failing test**

```java
package com.ecommerce.shop.auth;

import com.ecommerce.shop.auth.dto.RegisterRequest;
import com.ecommerce.shop.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.hamcrest.Matchers.not;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AuthControllerRefreshTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @AfterEach
    void cleanUp() {
        userRepository.deleteAll();
    }

    private String loginAndGetRefreshToken() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                .contentType(APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new RegisterRequest("refresh@example.com", "password123", "CUSTOMER"))));

        String loginBody = objectMapper.writeValueAsString(new LoginBody("refresh@example.com", "password123"));
        String response = mockMvc.perform(post("/api/auth/login").contentType(APPLICATION_JSON).content(loginBody))
                .andReturn().getResponse().getContentAsString();

        JsonNode json = objectMapper.readTree(response);
        return json.get("refreshToken").asString();
    }

    @Test
    void refreshWithValidTokenReturnsNewTokenPair() throws Exception {
        String refreshToken = loginAndGetRefreshToken();
        String body = objectMapper.writeValueAsString(new RefreshBody(refreshToken));

        mockMvc.perform(post("/api/auth/refresh").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").value(not(refreshToken)));
    }

    @Test
    void reusingRotatedRefreshTokenReturns401() throws Exception {
        String refreshToken = loginAndGetRefreshToken();
        String body = objectMapper.writeValueAsString(new RefreshBody(refreshToken));

        mockMvc.perform(post("/api/auth/refresh").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/refresh").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void refreshWithGarbageTokenReturns401() throws Exception {
        String body = objectMapper.writeValueAsString(new RefreshBody("not-a-real-token"));

        mockMvc.perform(post("/api/auth/refresh").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
    }

    private record LoginBody(String email, String password) {
    }

    private record RefreshBody(String refreshToken) {
    }
}
```

- [x] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=AuthControllerRefreshTest`
Expected: FAIL to compile — no `/api/auth/refresh` mapping, no `RefreshRequest`, no `AuthService.refresh`.

- [x] **Step 3: Write `RefreshRequest.java`**

```java
package com.ecommerce.shop.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record RefreshRequest(@NotBlank String refreshToken) {
}
```

- [x] **Step 4: Replace `AuthService.java` with the full updated version**

```java
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
```

> **Deviation from original plan (discovered during doc-alignment check):** the first version of `refresh()` called `refreshTokenRepository.deleteById(...)` instead of flag-based revocation, contradicting both the spec's "revokes the old" language and `RefreshToken.revoked`'s own purpose (checked via `stored.isRevoked()` but never set `true` anywhere). Switched to a `revoke()` helper that saves the row back with `revoked=true`, kept for Task 10's `logout()` to reuse — rows now persist after rotation/logout instead of being deleted, matching the spec and the "cheap DB flag flip" reasoning in the concepts doc.

- [x] **Step 5: Add the refresh mapping to `AuthController.java`**

Add this method inside the existing `AuthController` class (alongside `register` and `login`), and add `import com.ecommerce.shop.auth.dto.RefreshRequest;` to the imports:

```java
    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        return ResponseEntity.ok(authService.refresh(request));
    }
```

- [x] **Step 6: Run test to verify it passes**

Run: `./mvnw test -Dtest=AuthControllerRefreshTest`
Expected: `Tests run: 3, Failures: 0, Errors: 0`.

Also re-run Tasks 7 and 8's tests to confirm no regression: `./mvnw test -Dtest=AuthControllerRegisterTest,AuthControllerLoginTest` → both green.

- [x] **Step 7: Commit**

```bash
git add src/main/java/com/ecommerce/shop/auth src/test/java/com/ecommerce/shop/auth/AuthControllerRefreshTest.java
git commit -m "feat: add refresh endpoint with refresh token rotation"
```

---

### Task 10: Logout endpoint & full auth flow test

**Files:**
- Modify: `src/main/java/com/ecommerce/shop/auth/AuthService.java`
- Modify: `src/main/java/com/ecommerce/shop/auth/AuthController.java`
- Test: `src/test/java/com/ecommerce/shop/auth/AuthControllerLogoutAndFlowTest.java`

**Interfaces:**
- Consumes: `RefreshTokenRepository` (Task 6), `JwtService.parseRefreshToken` (Task 3), `RefreshRequest` (Task 9, reused — logout takes the same `{ refreshToken }` shape as refresh).
- Produces: `AuthService.logout(RefreshRequest): void`; `POST /api/auth/logout` → `204`.

- [ ] **Step 1: Write the failing test**

```java
package com.ecommerce.shop.auth;

import com.ecommerce.shop.auth.dto.RegisterRequest;
import com.ecommerce.shop.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AuthControllerLogoutAndFlowTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @AfterEach
    void cleanUp() {
        userRepository.deleteAll();
    }

    @Test
    void logoutRevokesRefreshTokenSoItCanNoLongerBeUsed() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                .contentType(APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new RegisterRequest("logout@example.com", "password123", "CUSTOMER"))));

        String loginResponse = mockMvc.perform(post("/api/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginBody("logout@example.com", "password123"))))
                .andReturn().getResponse().getContentAsString();
        String refreshToken = objectMapper.readTree(loginResponse).get("refreshToken").asString();

        mockMvc.perform(post("/api/auth/logout")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RefreshBody(refreshToken))))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RefreshBody(refreshToken))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void fullRegisterLoginRefreshLogoutFlowAndAdminRoleCheck() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RegisterRequest("flow@example.com", "password123", "CUSTOMER"))))
                .andExpect(status().isCreated());

        String loginResponse = mockMvc.perform(post("/api/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginBody("flow@example.com", "password123"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode loginJson = objectMapper.readTree(loginResponse);
        String accessToken = loginJson.get("accessToken").asString();
        String refreshToken = loginJson.get("refreshToken").asString();

        mockMvc.perform(get("/api/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("flow@example.com"));

        mockMvc.perform(get("/api/admin/ping").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isForbidden());

        String refreshResponse = mockMvc.perform(post("/api/auth/refresh")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RefreshBody(refreshToken))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String newRefreshToken = objectMapper.readTree(refreshResponse).get("refreshToken").asString();

        mockMvc.perform(post("/api/auth/logout")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RefreshBody(newRefreshToken))))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RefreshBody(newRefreshToken))))
                .andExpect(status().isUnauthorized());
    }

    private record LoginBody(String email, String password) {
    }

    private record RefreshBody(String refreshToken) {
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=AuthControllerLogoutAndFlowTest`
Expected: FAIL — no `/api/auth/logout` mapping (`404`) and no `AuthService.logout`.

- [ ] **Step 3: Add `logout` to `AuthService.java`**

Add this method inside the existing `AuthService` class (alongside `register`, `login`, `refresh`, `revoke`, `issueTokenPair`), and add `import com.ecommerce.shop.auth.dto.RefreshRequest;` if not already present (it was added in Task 9):

```java
    public void logout(RefreshRequest request) {
        Claims claims = jwtService.parseRefreshToken(request.refreshToken());
        String jti = claims.getId();

        refreshTokenRepository.findByJti(jti).ifPresent(this::revoke);
    }
```

Reuses the `revoke()` helper Task 9 added — logout revokes the same way rotation does (flag flip, not delete), so a revoked row still exists afterward and `findByJti` on it fails the `isRevoked()` check in `refresh()`, rather than failing to find it at all. Both look like 401 to the caller either way.

- [ ] **Step 4: Add the logout mapping to `AuthController.java`**

Add this method inside the existing `AuthController` class:

```java
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody RefreshRequest request) {
        authService.logout(request);
        return ResponseEntity.noContent().build();
    }
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./mvnw test -Dtest=AuthControllerLogoutAndFlowTest`
Expected: `Tests run: 2, Failures: 0, Errors: 0`.

- [ ] **Step 6: Run the full test suite**

Run: `./mvnw test`
Expected: `BUILD SUCCESS`, all tests across every task green.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/ecommerce/shop/auth src/test/java/com/ecommerce/shop/auth/AuthControllerLogoutAndFlowTest.java
git commit -m "feat: add logout endpoint and full auth flow integration test"
```
