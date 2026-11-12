-- Email replaces the phone number as the login identity (one-time code + JWT).
-- Phone stays for existing data but is no longer required.

-- The course schema created tb_user with ROW_FORMAT=COMPACT (767-byte index keys); a full-length
-- utf8mb4 email needs DYNAMIC, which is MySQL 8's default.
ALTER TABLE tb_user ROW_FORMAT=DYNAMIC;

ALTER TABLE tb_user
  ADD COLUMN email VARCHAR(254) NULL AFTER id,
  MODIFY COLUMN phone VARCHAR(11) NULL,
  ADD UNIQUE KEY uk_user_email (email);

-- Give seeded and existing accounts a deterministic demo address so they can still sign in.
UPDATE tb_user SET email = CONCAT('user', id, '@example.com') WHERE email IS NULL;
