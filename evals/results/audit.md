# Completion audit eval, prompt v2

Run 2026-11-19 03:01 (Asia/Dhaka). 100 jobs, 32 really done.

Checklist items judged right (MET vs not): 101 / 105 (96.2%). Jobs with no usable answer: 0.

## The policy at each threshold

Approved without a person when every item is MET and confidence >= threshold.

| Threshold | True approvals | **False approvals** | Done, sent to a person | Not done, sent to a person | Automated |
|---|---|---|---|---|---|
| 0.00 | 29 | 0 | 3 | 68 | 91% of done jobs |
| 0.50 | 29 | 0 | 3 | 68 | 91% of done jobs |
| 0.60 | 29 | 0 | 3 | 68 | 91% of done jobs |
| 0.70 | 29 | 0 | 3 | 68 | 91% of done jobs |
| 0.80 | 29 | 0 | 3 | 68 | 91% of done jobs |
| 0.85 | 29 | 0 | 3 | 68 | 91% of done jobs |
| 0.90 | 29 | 0 | 3 | 68 | 91% of done jobs |
| 0.95 | 27 | 0 | 5 | 68 | 84% of done jobs |
| 1.00 | 25 | 0 | 7 | 68 | 78% of done jobs |

Chosen threshold: **0.90**, the highest with no false approval that still automates the most done jobs.

## Confusion matrix at 0.90

| | Approved by the policy | Sent to a person |
|---|---|---|
| **Really done** | 29 | 3 |
| **Not done** | 0 | 68 |

## What went wrong, per job

Jobs where the AI's MET/not-MET differs from the label, and false approvals at 0.00.

| Job | Kind | Expected | AI said | Confidence |
|---|---|---|---|---|
| done-03 | done | [MET] | [UNCLEAR]: The photo shows multiple ATMs in a line at an indoor location, but the screens are dark and it is impossible to determine service status. | 0.90 |
| done-25 | done | [MET] | [NOT_MET]: The photo shows a portable generator on a trailer hooked up to a van in a street setting, rather than installed on site. | 0.90 |
| done-27 | done | [MET] | [NOT_MET]: The photo shows the generator loaded on a trailer attached to a truck on a city street, not installed on site. | 0.90 |
| partial-04 | partial | [MET, UNCLEAR] | [UNCLEAR, NOT_MET]: The photo shows multiple ATMs at their location, but the screens are dark and it is not possible to determine if they are in service. | 0.95 |
