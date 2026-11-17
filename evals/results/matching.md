# Matching eval

Run 2026-11-18 02:41 (Asia/Dhaka). 30 jobs, 125 technicians. Weights: semantic 0.5, skills 0.2, distance 0.15, rating 0.1, reliability 0.05.

| Ranking | Precision@5 |
|---|---|
| Tags only (Phase 1) | 0.03 |
| Hybrid | 0.78 |
| Hybrid without semantic | 0.05 |
| Semantic only | 0.93 |
| Hybrid 0.5/0.2/0.15/0.1/0.05 | 0.78 |
| Hybrid 0.6/0.15/0.1/0.1/0.05 | 0.87 |

## Per job (relevant among the 5 offered)

| Job | Tags only (Phase 1) | Hybrid | Hybrid without semantic | Semantic only | Hybrid 0.5/0.2/0.15/0.1/0.05 | Hybrid 0.6/0.15/0.1/0.1/0.05 |
|---|---|---|---|---|---|---|
| canon-copier-1 | 0 | 2 | 0 | 4 | 2 | 3 |
| canon-copier-2 | 0 | 2 | 0 | 4 | 2 | 3 |
| receipt-printer-1 | 0 | 5 | 0 | 5 | 5 | 5 |
| hp-laser-1 | 0 | 5 | 0 | 5 | 5 | 5 |
| wifi-1 | 0 | 2 | 0 | 5 | 2 | 3 |
| wifi-2 | 0 | 2 | 0 | 5 | 2 | 3 |
| fibre-1 | 0 | 5 | 0 | 5 | 5 | 5 |
| router-vpn-1 | 0 | 5 | 0 | 5 | 5 | 5 |
| ip-camera-1 | 0 | 5 | 0 | 5 | 5 | 5 |
| ip-camera-2 | 0 | 3 | 0 | 4 | 3 | 4 |
| vault-cctv-1 | 0 | 5 | 0 | 5 | 5 | 5 |
| split-ac-1 | 0 | 5 | 0 | 5 | 5 | 5 |
| split-ac-2 | 0 | 4 | 0 | 5 | 4 | 5 |
| server-cooling-1 | 1 | 5 | 1 | 5 | 5 | 5 |
| atm-dispenser-1 | 0 | 3 | 0 | 5 | 3 | 4 |
| atm-dispenser-2 | 0 | 2 | 0 | 5 | 2 | 5 |
| atm-card-reader-1 | 0 | 1 | 0 | 0 | 1 | 2 |
| office-wiring-1 | 0 | 5 | 0 | 5 | 5 | 5 |
| office-wiring-2 | 0 | 5 | 0 | 5 | 5 | 5 |
| industrial-1 | 1 | 5 | 1 | 5 | 5 | 5 |
| diesel-gen-1 | 0 | 3 | 1 | 5 | 3 | 3 |
| diesel-gen-2 | 0 | 3 | 1 | 5 | 3 | 4 |
| card-machine-1 | 0 | 4 | 0 | 4 | 4 | 4 |
| card-machine-2 | 0 | 4 | 0 | 5 | 4 | 4 |
| rooftop-solar-1 | 1 | 5 | 1 | 5 | 5 | 5 |
| rooftop-solar-2 | 1 | 4 | 1 | 5 | 4 | 5 |
| card-doors-1 | 0 | 3 | 1 | 3 | 3 | 3 |
| biometric-1 | 0 | 5 | 0 | 5 | 5 | 5 |
| receipt-printer-2 | 0 | 5 | 0 | 5 | 5 | 5 |
| hp-laser-2 | 0 | 5 | 0 | 5 | 5 | 5 |
