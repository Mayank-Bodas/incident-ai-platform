-- V2: Seed additional users for engineer and viewer roles
-- Password for all users: admin123
-- BCrypt hash generated with strength 12

INSERT INTO users (email, password_hash, first_name, last_name, role)
VALUES (
    'engineer@incidentplatform.com',
    '$2a$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQJqhN8/LewjFzDZ/GtyLhFau',
    'Jane',
    'Engineer',
    'ROLE_ENGINEER'
) ON CONFLICT (email) DO NOTHING;

INSERT INTO users (email, password_hash, first_name, last_name, role)
VALUES (
    'viewer@incidentplatform.com',
    '$2a$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQJqhN8/LewjFzDZ/GtyLhFau',
    'John',
    'Viewer',
    'ROLE_VIEWER'
) ON CONFLICT (email) DO NOTHING;
