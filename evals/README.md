# Evals

Checks of AI features against a fixed set of examples with known good answers (T29). Unlike unit tests they call a real model, whose answers vary, so they report **accuracy per field** instead of pass or fail. They are run on purpose, not in the normal build or CI: they take minutes and spend the free tier's daily quota.

## Intake (step 3.3)

`intake/cases.jsonl`: 50 messages a bank or office might send, in English (20), Banglish (15) and Bangla (15), each with what a good draft contains:

| Field | Scored as right when |
|---|---|
| skills | the draft has every required skill and nothing outside required plus `alsoOk` |
| date | the time window starts on the expected day (Asia/Dhaka) |
| window | the start hour fits the part of day: morning 8-12, afternoon 12-17, evening 17-21, a whole day 09:00 to 15:00 or later, urgent within 2 hours |
| urgency | URGENT or NORMAL as expected |
| address | contains one of the expected names (either script); `[]` means the message names no place, so there should be none |
| complete | a draft the dispatcher can use: title, time window and checklist filled |

A field whose right answer is open (`null` in the case) isn't scored. Every case is anchored to the same "now", Wednesday 18 November 2026 at 10:00 in Dhaka, so "tomorrow" and "Saturday" have one right answer. The set includes a prompt-injection attempt (`en-17`) and a message with no details at all (`en-16`).

Run it from `app/`:

```bash
export $(grep ^GEMINI_API_KEY= ../.env)
./mvnw test -Dtest=IntakeEvals -DexcludedGroups=none
```

- **Recordings.** Every answer is saved in `intake/recordings/<feature>/<prompt version>/`, keyed by a hash of the exact prompt. A re-run replays them: free, fast, and the same result. A new prompt version, a changed case or a different skill list is a new key, so only those cases call the model. Delete a version's folder to ask the model again (answers vary between runs).
- **Pacing.** Real calls are spaced 5 s apart, under the free tier's 15 requests a minute: about 7 minutes for 50 new cases.
- **Results.** The report prints and is written to `results/intake-<prompt version>.md`: accuracy per field and per language, and every wrong answer with what was expected.

To try a prompt change, copy `app/src/main/resources/prompts/intake/v2.st` to `v3.st`, edit it, set `IntakeParser.PROMPT_VERSION` to `v3`, run, and compare `results/intake-v2.md` with `results/intake-v3.md`.
