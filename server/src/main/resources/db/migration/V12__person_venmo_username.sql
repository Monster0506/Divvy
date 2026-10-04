-- Where a person receives payments, so settle-up can open Venmo on the right account.
--
-- Stored without the leading "@". Venmo usernames are 5 to 30 letters, digits, hyphens and
-- underscores. Divvy only keeps the username: no payment credentials are ever stored.
ALTER TABLE person
    ADD COLUMN venmo_username text
        CONSTRAINT person_venmo_username_check CHECK (venmo_username ~ '^[A-Za-z0-9_-]{5,30}$');
