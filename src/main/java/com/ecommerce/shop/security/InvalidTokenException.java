package com.ecommerce.shop.security;

import com.ecommerce.shop.common.exception.ApiException;
import org.springframework.http.HttpStatus;

public class InvalidTokenException extends ApiException {
    public InvalidTokenException(String message) {
        super(HttpStatus.UNAUTHORIZED, message);
    }
}
