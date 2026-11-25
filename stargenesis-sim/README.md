# P0 simulation reference implementations

This module has **no Bukkit dependencies**. It supplies `FixedSimulationClock`,
`ConservingResourceLedger` and `MemoryEventOutbox` as executable P0 contracts, plus the bounded P1
resource sandbox with deterministic terrain, movement, gathering, deposits, state hashes and replay.
The Paper typed-program adapter sends whitelisted commands into that sandbox. It does not mutate a
Minecraft world, player inventory or real economy.

Construct each mutable instance on its owning simulation worker. Every state read and write checks
the owner thread. Only immutable snapshots/checkpoints may cross threads; `restore` creates a new
ledger owned by its caller. Names are bounded lowercase account/resource IDs, quantities use checked
integer arithmetic, accounts are limited to 4096 and balance entries to 16384.

The scenario provides all initial ledger balances. There is no mint API and no external inventory or
currency settlement. A transfer evidence key reserves the exact request and its original outcome.
Reapplying successful evidence returns `ALREADY_APPLIED`; an insufficient-funds result stays failed
even if balances later improve. A legitimate new attempt uses new evidence. Reusing a key for a
different request throws before mutation. Arithmetic overflow and exhausted budgets fail before
mutation. Checkpoints include failure/success history, so restoring does not lose deduplication.
Checkpoints are trusted internal values, not authenticated player import documents. A storage adapter
must version, authenticate, validate and persist them atomically; this module does not perform IO.

`MemoryEventOutbox` orders pending events by world/tick/sequence and retains acknowledged evidence
until the scenario is discarded. Delivery is at least once, so consumers must also deduplicate.
Unknown acknowledgements fail closed. Evidence capacity defaults to 16384 and is capped at 100000;
history is never silently evicted. **This outbox is memory-only and cannot provide crash recovery or
real economic transactions.** The P9 durable outbox/saga adapter remains required.

Run `mvn -B -pl stargenesis-sim -am verify` from the repository root. Tests cover deterministic logical
ticks, overflow, defensive copies, checked transfer conservation, 1000 seeded transfer operations,
checkpoint history, retry semantics, bounded delivery, program movement/harvesting, resource
conservation, replay validation and cross-thread rejection. Passing these tests does not satisfy
Paper/Folia client gameplay acceptance.
