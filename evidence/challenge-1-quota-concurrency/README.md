# Quota concurrency

Twenty concurrent requests compete for a STANDARD tenant's quota. Five succeed and fifteen
are rejected. Final usage reaches the CPU limit without exceeding it.

| Claim | Evidence |
|---|---|
| Repeatable concurrent requests and counted results | [Script](run.ps1), [output](output.txt) |
| Initial usage and final allocation | [Before](db-before.txt), [after](db-after.txt) |

The conditional SQL update makes admission atomic. Reading usage before updating it would let
concurrent requests admit allocations against the same remaining capacity.
