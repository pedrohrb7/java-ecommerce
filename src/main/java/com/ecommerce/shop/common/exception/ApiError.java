package com.ecommerce.shop.common.exception;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description = "Standard error body returned by GlobalExceptionHandler for any non-2xx response")
public record ApiError(Instant timestamp, int status, String error, String message, String path) {
}
