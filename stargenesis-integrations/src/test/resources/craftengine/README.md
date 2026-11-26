# Actual Paper item snapshot fixture

`captured.bin` and `decoded.bin` were produced on Paper 1.21.11-132 (DataVersion 4671), Microsoft Java 21, using the actual CraftEngine 26.9.1 provider whose SHA-256 is `021260c87e3546730d321f4360b1744e6e33caa1d68e98ae30dcdbbdc347ae0f`.

Source: `scripts/CraftEngineItemServerProbe.java`, run `.work/craftengine-bridge/paper-structural` on 2026-11-26. The original contains a stack of three custom paper items, max stack size four, template item name/model, custom display name, Chinese lore, byte-array PDC and nested PDC. The decoded file is Paper's immediate reserialization of the first file. Compound field order differs; complete typed NBT state is equal. Files are unmodified server output, not synthetic API substitutes.

That run's semantic item assertions passed but its harness rejected the deliberate provider-disable warning. The later restart run retained and explicitly classified that fixed warning. These golden bytes only prove the parser regression; server lifecycle evidence is recorded separately in `docs/evidence/p10/item-bridge/summary.json`.
