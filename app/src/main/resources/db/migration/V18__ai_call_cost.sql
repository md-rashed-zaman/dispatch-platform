-- What each AI call cost, at the list price when it was made (step 3.6, T32): prices change, and a cost computed
-- later from today's price table would rewrite history. NULL where no tokens were used (replayed, rate limited,
-- over budget) or the model has no price configured.
ALTER TABLE ai_call ADD COLUMN cost_usd NUMERIC(14, 8) CHECK (cost_usd >= 0);

-- Calls made before this column existed: at the list prices configured then (application.yml, step 3.2), so a
-- company's history doesn't show its first weeks as free.
UPDATE ai_call
SET cost_usd = (coalesce(input_tokens, 0) * 0.10 + coalesce(output_tokens, 0) * 0.40) / 1000000
WHERE outcome IN ('OK', 'ERROR', 'INVALID_OUTPUT')
  AND model IN ('gemini-flash-lite-latest', 'gemini-3.1-flash-lite');
