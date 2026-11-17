# Intake eval, prompt v1

Run 2026-11-18 00:34 (Asia/Dhaka), 50 cases.

| Field | Accuracy | Right / scored |
|---|---|---|
| skills | 100.0% | 50 / 50 |
| date | 100.0% | 36 / 36 |
| window | 89.1% | 41 / 46 |
| urgency | 100.0% | 48 / 48 |
| address | 100.0% | 50 / 50 |
| complete | 100.0% | 50 / 50 |
| **all fields** | 98.2% | 275 / 280 |

| Language | Accuracy | Right / scored |
|---|---|---|
| banglish | 96.4% | 81 / 84 |
| bn | 97.6% | 83 / 85 |
| en | 100.0% | 111 / 111 |

## Wrong answers

| Case | Field | Expected | Got |
|---|---|---|---|
| bl-01 | window | afternoon | 2026-11-19T17:00 to 20:00 |
| bl-04 | window | afternoon | 2026-11-18T17:00 to 20:00 |
| bl-13 | window | afternoon | 2026-11-23T17:00 to 20:00 |
| bn-03 | window | afternoon | 2026-11-19T17:00 to 20:00 |
| bn-13 | window | afternoon | 2026-11-23T17:00 to 20:00 |
