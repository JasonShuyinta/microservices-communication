package com.dotjson.customer;

public record CustomerRegistrationRequest(
        String firstName,
        String lastName,
        String email) {
}
