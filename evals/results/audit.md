# Completion audit eval, prompt v1

Run 2026-11-19 00:39 (Asia/Dhaka). 100 jobs, 32 really done.

Checklist items judged right (MET vs not): 103 / 105 (98.1%). Jobs with no usable answer: 0.

## The policy at each threshold

Approved without a person when every item is MET and confidence >= threshold.

| Threshold | True approvals | **False approvals** | Done, sent to a person | Not done, sent to a person | Automated |
|---|---|---|---|---|---|
| 0.00 | 30 | 0 | 2 | 68 | 94% of done jobs |
| 0.50 | 30 | 0 | 2 | 68 | 94% of done jobs |
| 0.60 | 30 | 0 | 2 | 68 | 94% of done jobs |
| 0.70 | 30 | 0 | 2 | 68 | 94% of done jobs |
| 0.80 | 30 | 0 | 2 | 68 | 94% of done jobs |
| 0.85 | 28 | 0 | 4 | 68 | 88% of done jobs |
| 0.90 | 28 | 0 | 4 | 68 | 88% of done jobs |
| 0.95 | 26 | 0 | 6 | 68 | 81% of done jobs |
| 1.00 | 26 | 0 | 6 | 68 | 81% of done jobs |

Chosen threshold: **0.80**, the highest with no false approval that still automates the most done jobs.

## Confusion matrix at 0.80

| | Approved by the policy | Sent to a person |
|---|---|---|
| **Really done** | 30 | 2 |
| **Not done** | 0 | 68 |

## What went wrong, per job

Jobs where the AI's MET/not-MET differs from the label, and false approvals at 0.00.

| Job | Kind | Expected | AI said | Confidence |
|---|---|---|---|---|
| done-25 | done | [MET] | [UNCLEAR]: The photo shows a yellow towable generator unit parked on a street next to a van, but it appears to be mobile transport rather than installed in place on site. | 0.40 |
| done-27 | done | [MET] | [UNCLEAR]: The photo shows a generator mounted on a trailer attached to a truck on a street, but it is not clear if this is the final intended site location. | 0.50 |
