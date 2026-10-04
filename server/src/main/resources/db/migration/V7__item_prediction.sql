-- What the ML service predicted for each receipt item, kept next to (not instead of) item_owner.
--
-- item_owner holds the owners an item has now, whether a person chose them or the model
-- predicted them. This table records the model's side: which model version said what, how sure it
-- was, and the other owner sets it considered. A prediction survives a person overruling it, so
-- corrections can be studied, and an "unknown" answer is recorded too.
CREATE TABLE item_prediction
(
    receipt_item_id uuid          PRIMARY KEY,
    household_id    uuid          NOT NULL,
    model_version   text          NOT NULL,
    status          text          NOT NULL CHECK (status IN ('predicted', 'unknown')),
    -- Probability the predicted owner set is right; a probability, not money.
    confidence      numeric(5, 4) NOT NULL CHECK (confidence BETWEEN 0 AND 1),
    -- JSON array of {"owners": [person ids], "confidence": number}.
    alternatives    text          NOT NULL DEFAULT '[]',
    created_at      timestamptz   NOT NULL DEFAULT now(),
    updated_at      timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT item_prediction_item_fkey FOREIGN KEY (household_id, receipt_item_id)
        REFERENCES receipt_item (household_id, id) ON DELETE CASCADE
);

CREATE INDEX item_prediction_household_idx ON item_prediction (household_id);

CREATE TRIGGER item_prediction_set_updated_at
    BEFORE UPDATE ON item_prediction
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
