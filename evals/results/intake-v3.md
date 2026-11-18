# Intake eval, prompt v3

Run 2026-11-19 03:14 (Asia/Dhaka), 54 cases.

| Field | Accuracy | Right / scored |
|---|---|---|
| skills | 96.3% | 52 / 54 |
| date | 100.0% | 39 / 39 |
| window | 100.0% | 49 / 49 |
| urgency | 100.0% | 52 / 52 |
| address | 100.0% | 54 / 54 |
| complete | 100.0% | 54 / 54 |
| **all fields** | 99.3% | 300 / 302 |

| Language | Accuracy | Right / scored |
|---|---|---|
| banglish | 100.0% | 84 / 84 |
| bn | 100.0% | 91 / 91 |
| en | 98.4% | 125 / 127 |

## Wrong answers

| Case | Field | Expected | Got |
|---|---|---|---|
| en-06 | skills | [ac-repair] | [ac-repair, electrical] |
| en-12 | skills | [atm] | [atm, electrical] |
