package com.ecommerce.shop.demo;

import com.ecommerce.shop.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Demo", description = "Protected endpoints used to demonstrate the JWT auth/authorization flow.")
public class DemoController {

    @GetMapping("/api/me")
    @Operation(summary = "Get the current user", description = "Returns the id/email/role of whoever the access token belongs to. Any authenticated role.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Current user",
                    content = @Content(schema = @Schema(implementation = MeResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing, invalid, or expired access token (empty body - raised by the security filter chain, not GlobalExceptionHandler)")
    })
    public MeResponse me(@AuthenticationPrincipal AuthenticatedUser user) {
        return new MeResponse(user.id(), user.email(), user.role());
    }

    @GetMapping("/api/admin/ping")
    @Operation(summary = "Admin role check", description = "Trivial endpoint that only succeeds for ADMIN accounts; used to demonstrate role-based authorization.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "\"pong\""),
            @ApiResponse(responseCode = "401", description = "Missing, invalid, or expired access token (empty body - raised by the security filter chain, not GlobalExceptionHandler)"),
            @ApiResponse(responseCode = "403", description = "Authenticated but not an ADMIN (empty body - raised by the security filter chain, not GlobalExceptionHandler)")
    })
    public String adminPing() {
        return "pong";
    }
}
