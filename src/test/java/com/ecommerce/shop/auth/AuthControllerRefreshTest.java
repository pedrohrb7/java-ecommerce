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
