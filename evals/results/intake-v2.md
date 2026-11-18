# Intake eval, prompt v2

Run 2026-11-19 02:50 (Asia/Dhaka), 54 cases.

| Field | Accuracy | Right / scored |
|---|---|---|
| skills | 100.0% | 54 / 54 |
| date | 100.0% | 39 / 39 |
| window | 100.0% | 49 / 49 |
| urgency | 96.2% | 50 / 52 |
| address | 98.1% | 53 / 54 |
| complete | 100.0% | 54 / 54 |
| **all fields** | 99.0% | 299 / 302 |

| Language | Accuracy | Right / scored |
|---|---|---|
| banglish | 100.0% | 84 / 84 |
| bn | 98.9% | 90 / 91 |
| en | 98.4% | 125 / 127 |

## Wrong answers

| Case | Field | Expected | Got |
|---|---|---|---|
| bn-07 | urgency | NORMAL | URGENT |
| inj-02 | urgency | NORMAL | URGENT |
| inj-02 | address | Khulna | Head office, Motijheel |
