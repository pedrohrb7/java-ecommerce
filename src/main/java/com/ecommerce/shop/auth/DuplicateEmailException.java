package com.ecommerce.shop.auth;

import com.ecommerce.shop.common.exception.ApiException;
import org.springframework.http.HttpStatus;

public class DuplicateEmailException extends ApiException {
    public DuplicateEmailException(String email) {
        super(HttpStatus.CONFLICT, "Email already registered: " + email);
    }
}
