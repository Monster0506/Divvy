-- Append-only ledger of money owed between household members.
--
-- Each row says debtor owes creditor amount_minor. Rows are never changed or deleted by the
-- application: a mistake is undone by inserting a reversing entry that points at the original
-- through reverses_entry_id. Balances are the sum of the entries.
--
-- source_id refers to a receipt or settlement depending on source_type, so it has no foreign key.
CREATE TABLE ledger_entry
(
    id                 uuid PRIMARY KEY     DEFAULT gen_random_uuid(),
    household_id       uuid        NOT NULL REFERENCES household (id) ON DELETE CASCADE,
    debtor_person_id   uuid        NOT NULL,
    creditor_person_id uuid        NOT NULL,
    amount_minor       bigint      NOT NULL CHECK (amount_minor > 0),
    currency           char(3)     NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    source_type        text        NOT NULL CHECK (source_type IN ('receipt', 'settlement')),
    source_id          uuid        NOT NULL,
    reverses_entry_id  uuid,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ledger_entry_household_id_id_key UNIQUE (household_id, id),
    CONSTRAINT ledger_entry_distinct_parties_check CHECK (debtor_person_id <> creditor_person_id),
    CONSTRAINT ledger_entry_debtor_fkey FOREIGN KEY (household_id, debtor_person_id)
        REFERENCES person (household_id, id) ON DELETE NO ACTION DEFERRABLE INITIALLY IMMEDIATE,
    CONSTRAINT ledger_entry_creditor_fkey FOREIGN KEY (household_id, creditor_person_id)
        REFERENCES person (household_id, id) ON DELETE NO ACTION DEFERRABLE INITIALLY IMMEDIATE,
    CONSTRAINT ledger_entry_reverses_fkey FOREIGN KEY (household_id, reverses_entry_id)
        REFERENCES ledger_entry (household_id, id) ON DELETE CASCADE
);

-- An entry can be reversed at most once.
CREATE UNIQUE INDEX ledger_entry_reverses_key ON ledger_entry (reverses_entry_id)
    WHERE reverses_entry_id IS NOT NULL;
CREATE INDEX ledger_entry_household_created_idx ON ledger_entry (household_id, created_at);
CREATE INDEX ledger_entry_debtor_idx ON ledger_entry (household_id, debtor_person_id);
CREATE INDEX ledger_entry_creditor_idx ON ledger_entry (household_id, creditor_person_id);
CREATE INDEX ledger_entry_source_idx ON ledger_entry (source_type, source_id);

CREATE FUNCTION reject_ledger_update() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'ledger_entry rows are append-only; insert a reversing entry instead';
END;
$$;

CREATE TRIGGER ledger_entry_no_update
    BEFORE UPDATE ON ledger_entry
    FOR EACH ROW EXECUTE FUNCTION reject_ledger_update();
