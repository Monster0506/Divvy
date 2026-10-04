-- Append-only log of every change to item ownership.
--
-- item_owner holds the current assignment; this table keeps every previous one, so a model
-- prediction that a person later corrected is not lost. That correction history is the
-- training signal for ownership learning (M2).
--
-- No foreign keys on purpose: log rows must outlive the rows they describe.
CREATE TABLE item_owner_history
(
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    household_id    uuid          NOT NULL,
    receipt_item_id uuid          NOT NULL,
    person_id       uuid          NOT NULL,
    operation       text          NOT NULL CHECK (operation IN ('assigned', 'changed', 'unassigned')),
    share_weight    integer       NOT NULL,
    source          text          NOT NULL,
    confidence      numeric(5, 4),
    changed_at      timestamptz   NOT NULL DEFAULT now(),
    -- Present for symmetry with the other tables; history rows are never updated.
    created_at      timestamptz   NOT NULL DEFAULT now(),
    updated_at      timestamptz   NOT NULL DEFAULT now()
);

CREATE INDEX item_owner_history_item_idx ON item_owner_history (receipt_item_id, changed_at);
CREATE INDEX item_owner_history_household_idx ON item_owner_history (household_id, changed_at);

CREATE FUNCTION log_item_owner_change() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF TG_OP = 'INSERT' THEN
        INSERT INTO item_owner_history (household_id, receipt_item_id, person_id, operation,
                                        share_weight, source, confidence)
        VALUES (NEW.household_id, NEW.receipt_item_id, NEW.person_id, 'assigned',
                NEW.share_weight, NEW.source, NEW.confidence);
        RETURN NEW;
    ELSIF TG_OP = 'UPDATE' THEN
        IF (OLD.share_weight, OLD.source, OLD.confidence)
            IS NOT DISTINCT FROM (NEW.share_weight, NEW.source, NEW.confidence) THEN
            RETURN NEW;
        END IF;
        INSERT INTO item_owner_history (household_id, receipt_item_id, person_id, operation,
                                        share_weight, source, confidence)
        VALUES (NEW.household_id, NEW.receipt_item_id, NEW.person_id, 'changed',
                NEW.share_weight, NEW.source, NEW.confidence);
        RETURN NEW;
    ELSE
        INSERT INTO item_owner_history (household_id, receipt_item_id, person_id, operation,
                                        share_weight, source, confidence)
        VALUES (OLD.household_id, OLD.receipt_item_id, OLD.person_id, 'unassigned',
                OLD.share_weight, OLD.source, OLD.confidence);
        RETURN OLD;
    END IF;
END;
$$;

CREATE TRIGGER item_owner_log_change
    AFTER INSERT OR UPDATE OR DELETE ON item_owner
    FOR EACH ROW EXECUTE FUNCTION log_item_owner_change();