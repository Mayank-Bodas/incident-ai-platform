package com.incidentplatform.application.dto.response;

import lombok.Builder;

/**
 * AuthResponse — Returned upon successful authentication, containing the JWT and user metadata.
 */
@Builder
public record AuthResponse(
    String token,
    String email,
    String role,
    String firstName,
    String lastName
) {}
