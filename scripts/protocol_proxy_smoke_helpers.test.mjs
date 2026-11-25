import assert from 'node:assert/strict'
import test from 'node:test'
import { componentText, inventoryItemText, offlinePlayerUuid } from './protocol_proxy_smoke_helpers.mjs'

test('computes the standard deterministic offline player UUID', () => {
  assert.equal(offlinePlayerUuid('SgRelaySource'), 'ed72fb90-7439-3e52-94a4-639a0a05eff3')
  assert.equal(offlinePlayerUuid('SgRelayTarget'), 'd0b5e70c-681a-3409-b706-b8dcb1ff5dce')
})

test('reads plain and serialized chat components', () => {
  assert.equal(componentText('plain text'), 'plain text')
  assert.equal(componentText('{"text":"Hello","extra":[{"text":" world"}]}'), 'Hello world')
  assert.equal(componentText([{ text: 'one' }, { text: 'two' }]), 'onetwo')
})

test('extracts inventory labels and lore from Mineflayer item metadata', () => {
  assert.match(inventoryItemText({
    customName: '{"text":"来自 SgRelaySource"}',
    customLore: ['genesis:protocol', '载荷 sequence=2:42']
  }), /载荷 sequence=2:42/)
})

test('extracts payload lore encoded in nested Minecraft NBT', () => {
  const item = {
    nbt: {
      type: 'compound',
      value: {
        components: {
          type: 'compound',
          value: {
            'minecraft:lore': {
              type: 'list',
              value: {
                type: 'string',
                value: ['载荷 sequence=2:42']
              }
            }
          }
        }
      }
    }
  }
  assert.match(inventoryItemText(item), /载荷 sequence=2:42/)
})
