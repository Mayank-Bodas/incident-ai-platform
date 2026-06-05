package com.incidentplatform.domain.enums;

/**
 * UserRole — Role-Based Access Control (RBAC)
 *
 * WHY RBAC over ACL (Access Control Lists)?
 * ACL: per-user permissions → doesn't scale with 1000 users
 * RBAC: per-role permissions → manage 3 roles instead of 1000 users
 * Most enterprise systems use RBAC. AWS IAM, GCP IAM, Kubernetes all use it.
 *
 * Spring Security convention: roles are prefixed with "ROLE_"
 * When you call hasRole("ADMIN"), Spring looks for "ROLE_ADMIN" in authorities.
 *
 * Interview: "Difference between @PreAuthorize hasRole vs hasAuthority?"
 * → hasRole adds ROLE_ prefix automatically, hasAuthority checks exact string
 */
public enum UserRole {
    ROLE_ADMIN,     // Full access: manage users, configure agents, view everything
    ROLE_ENGINEER,  // Create/resolve incidents, view all data
    ROLE_VIEWER     // Read-only: view incidents, reports, dashboards
}
