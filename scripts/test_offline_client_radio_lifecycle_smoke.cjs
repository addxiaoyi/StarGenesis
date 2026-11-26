"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const { sameCommittedRadio } = require("./offline_client_radio_lifecycle_smoke.cjs");

function snapshot() {
  return { schemaVersion: 1, radioProtocolId: "radio", radioProtocolHash: "r", serviceProtocolId: "service",
    serviceProtocolHash: "s", contractId: "contract", contractHash: "c",
    radio: { tick: 10, evidence: [{ sequence: 1 }], acknowledged: [], pending: [{ sequence: 1 }], events: [{ tick: 9 }] },
    contracts: { tick: 10, states: [{ id: "order", requester: "a", provider: "b", createdAt: 9, expiresAt: 20,
      requestedUnits: 3, fulfilledUnits: 0, state: "OFFERED" }], received: [{ sequence: 2 }], events: [{ tick: 9 }] } };
}

test("restart evidence allows clock progress and only the exact due expiration", () => {
  const before = snapshot();
  const advanced = structuredClone(before);
  advanced.radio.tick = advanced.contracts.tick = 21;
  advanced.contracts.states[0].state = "EXPIRED";
  advanced.contracts.events.push({ tick: 21, type: "contract.expire", contractId: "order", actor: "b", peer: "a",
    units: 0, state: "EXPIRED", sequence: -1 });
  sameCommittedRadio(advanced, before);
  for (const mutate of [doc => doc.contracts.events.at(-1).tick++, doc => doc.contracts.events.pop(),
    doc => doc.contracts.states[0].state = "COMPLETED", doc => doc.contracts.tick++,
    doc => doc.contracts.states[0].fulfilledUnits++]) {
    const invalid = structuredClone(advanced);
    mutate(invalid);
    assert.throws(() => sameCommittedRadio(invalid, before));
  }
});

test("restart evidence rejects pending loss, altered acknowledgements and rewritten history", () => {
  const before = snapshot();
  sameCommittedRadio(structuredClone(before), before);
  for (const mutate of [doc => doc.radio.pending.pop(), doc => doc.radio.acknowledged.push({ sequence: 1 }),
    doc => doc.radio.evidence[0].sequence++, doc => doc.radio.events[0].tick++,
    doc => doc.contracts.received.pop(), doc => doc.contracts.events[0].tick++,
    doc => doc.contracts.states.pop(), doc => doc.contractHash = "changed"]) {
    const invalid = structuredClone(before);
    mutate(invalid);
    assert.throws(() => sameCommittedRadio(invalid, before));
  }
});
