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
