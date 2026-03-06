package com.bitman.justbuy.dto;

public record AuthResponse(
    String token,
    UserDto user
) {}
