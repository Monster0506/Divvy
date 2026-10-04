-- list_item: one entry on a household's shared grocery list.
--
-- Items are deleted outright when someone removes them; the list is a to-do, not history.
-- added_by_person_id is optional because the app has no sign-in yet (#143), and it points at person
-- through the composite key like every other child table, so it can only name a member of the same
-- household.
CREATE TABLE list_item
(
    id                 uuid PRIMARY KEY     DEFAULT gen_random_uuid(),
    household_id       uuid        NOT NULL REFERENCES household (id) ON DELETE CASCADE,
    text               text        NOT NULL CHECK (length(btrim(text)) > 0),
    -- Free text so "2", "1 dozen" and "500 g" all fit; it is not used in any calculation.
    quantity           text,
    notes              text,
    added_by_person_id uuid,
    status             text        NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'purchased')),
    purchased_at       timestamptz,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT list_item_added_by_fkey FOREIGN KEY (household_id, added_by_person_id)
        REFERENCES person (household_id, id) ON DELETE NO ACTION DEFERRABLE INITIALLY IMMEDIATE,
    CONSTRAINT list_item_purchased_at_check CHECK (status <> 'purchased' OR purchased_at IS NOT NULL)
);

CREATE INDEX list_item_household_created_at_idx ON list_item (household_id, created_at);
CREATE INDEX list_item_added_by_idx ON list_item (household_id, added_by_person_id);

CREATE TRIGGER list_item_set_updated_at
    BEFORE UPDATE ON list_item
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
