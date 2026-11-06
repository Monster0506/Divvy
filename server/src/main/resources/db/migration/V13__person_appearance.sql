-- How a person's avatar looks in the app: a short name of one or two characters, an emoji icon,
-- a color from a fixed palette and an optional photo. All of them are optional; the app falls back
-- to the person's initials when nothing is set.
--
-- The photo bytes live in image storage like receipt images; photo_key is the storage key and
-- photo_updated_at changes on every upload so clients know when to refetch. Both are set together
-- or neither is.
ALTER TABLE person
    ADD COLUMN short_name text
        CONSTRAINT person_short_name_check CHECK (char_length(btrim(short_name)) BETWEEN 1 AND 2),
    ADD COLUMN emoji text
        CONSTRAINT person_emoji_check CHECK (char_length(emoji) BETWEEN 1 AND 16),
    ADD COLUMN avatar_color text
        CONSTRAINT person_avatar_color_check
            CHECK (avatar_color IN ('gray', 'green', 'teal', 'blue', 'purple', 'pink', 'orange', 'yellow')),
    ADD COLUMN photo_key text,
    ADD COLUMN photo_updated_at timestamptz,
    ADD CONSTRAINT person_photo_check CHECK ((photo_key IS NULL) = (photo_updated_at IS NULL));
