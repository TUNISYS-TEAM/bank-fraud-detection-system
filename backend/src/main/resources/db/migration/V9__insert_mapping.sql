INSERT INTO keycloak_mapping (keycloak_id, user_id, username, email, last_sync)
SELECT 'a47c71ed-a3cf-45b7-b410-3d2e5b594822', id, 'admin@fraud.com', 'admin@fraud.com', NOW()
FROM app_user 
WHERE email = 'admin@fraud.com';