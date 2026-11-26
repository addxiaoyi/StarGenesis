"""Reject missing or rewritten P4 rotation evidence without launching a server."""
import copy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest

from server_radio_rotation_smoke import (NAMES, active, archive_name, copy_journal, offline_identity, read_journal,
                                         same_committed_journal, validate_journal, validate_phase_journal,
                                         validate_workflow)


RADIO_HASH = 'a' * 64
SERVICE_HASH = 'b' * 64


def snapshot(sequence=1001, sender='requester', contract=False, tick=10):
    recipient = 'provider' if sender == 'requester' else 'requester'
    if contract:
        specifications = [('genesis:radio', RADIO_HASH, 1001, {'sequence': '1001', 'channel': 'rotation', 'value': 'rotation_payload'}),
                           ('genesis:service', SERVICE_HASH, 1002, {'contract': 'rotation_order', 'action': 'offer', 'units': '3'}),
                           ('genesis:service', SERVICE_HASH, 1003, {'contract': 'rotation_order', 'action': 'cancel', 'units': '0'})]
    else:
        value = {2001: 'next_segment_payload', 3001: 'after_second_rotation'}.get(sequence, 'rotation_payload')
        specifications = [('genesis:radio', RADIO_HASH, sequence,
                           {'sequence': str(sequence), 'channel': 'rotation', 'value': value})]
    messages = []
    for index, (protocol, protocol_hash, value, payload) in enumerate(specifications):
        messages.append({'protocol': {'value': protocol}, 'protocolHash': protocol_hash, 'sender': sender,
                         'recipient': recipient, 'tick': index * 2 + 1, 'sequence': value, 'payload': payload})
    radio_events = []
    for message in messages:
        radio_events.append({'tick': message['tick'], 'type': 'radio.sent', 'protocol': message['protocol'],
                             'protocolHash': message['protocolHash'], 'actor': message['sender'],
                             'peer': message['recipient'], 'sequence': message['sequence']})
        radio_events.append({'tick': message['tick'] + 1, 'type': 'radio.acknowledged', 'protocol': message['protocol'],
                             'protocolHash': message['protocolHash'], 'actor': message['recipient'],
                             'peer': message['sender'], 'sequence': message['sequence']})
    return {'schemaVersion': 1, 'radioProtocolId': 'genesis:radio', 'radioProtocolHash': RADIO_HASH,
            'serviceProtocolId': 'genesis:service', 'serviceProtocolHash': SERVICE_HASH,
            'contractId': 'genesis:service_contract', 'contractHash': 'c' * 64,
            'radio': {'tick': tick, 'evidence': messages,
                      'acknowledged': [{'protocol': message['protocol'], 'protocolHash': message['protocolHash'],
                                       'sender': message['sender'], 'sequence': message['sequence']} for message in messages],
                      'pending': [], 'events': radio_events},
            'contracts': {'tick': tick, 'states': [{'id': 'rotation_order', 'state': 'CANCELLED',
                          'requester': 'requester', 'provider': 'provider', 'requestedUnits': 3, 'fulfilledUnits': 0,
                          'createdAt': 5, 'expiresAt': 205}] if contract else [],
                          'received': copy.deepcopy(messages[1:]) if contract else [],
                          'events': [{'tick': 5, 'type': 'contract.offer', 'contractId': 'rotation_order',
                                      'actor': 'requester', 'peer': 'provider', 'units': 3, 'state': 'OFFERED', 'sequence': 1002},
                                     {'tick': 7, 'type': 'contract.cancel', 'contractId': 'rotation_order',
                                      'actor': 'requester', 'peer': 'provider', 'units': 0, 'state': 'CANCELLED', 'sequence': 1003}] if contract else []}}


def bundle(current, archives=()):
    files, stored, references = {}, {}, []
    for generation, archived in enumerate(archives, 1):
        raw = json.dumps(archived).encode('utf-8')
        name = archive_name(generation)
        files[name] = {'bytes': len(raw), 'sha256': hashlib.sha256(raw).hexdigest()}
        stored[name] = copy.deepcopy(archived)
        references.append({'generation': generation, **files[name]})
    document = {'schemaVersion': 2, 'active': current, 'archives': references} if archives else current
    raw = json.dumps(document).encode('utf-8')
    files['radio-state.json'] = {'bytes': len(raw), 'sha256': hashlib.sha256(raw).hexdigest()}
    return {'document': copy.deepcopy(document), 'files': files, 'archives': stored}


class RadioRotationEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.identities = {'requester': 'requester', 'provider': 'provider'}
        self.prepare = bundle(snapshot(contract=True))
        self.rotate = bundle(snapshot(2001, 'provider', tick=20), [active(self.prepare)])
        self.verify = bundle(snapshot(3001, 'requester', tick=30),
                             [active(self.prepare), active(self.rotate)])

    def test_three_phase_transition_accepts_only_expected_archives_and_active_identity(self):
        validate_phase_journal(self.prepare, 'prepare', self.identities, None)
        validate_phase_journal(self.rotate, 'rotate', self.identities, self.prepare)
        validate_phase_journal(self.verify, 'verify', self.identities, self.rotate)
        for mutate in (lambda doc: active(doc)['radio']['evidence'][0].update(sequence=999),
                       lambda doc: active(doc)['radio']['evidence'][0].update(recipient='wrong'),
                       lambda doc: active(doc)['radio']['evidence'][0]['payload'].update(value='wrong'),
                       lambda doc: active(doc)['radio']['acknowledged'][0].update(sender='wrong'),
                       lambda doc: active(doc)['radio']['acknowledged'][0].update(sequence=999),
                       lambda doc: active(doc)['radio']['events'][0].update(type='wrong'),
                       lambda doc: active(doc)['radio']['events'][0].update(actor='wrong'),
                       lambda doc: active(doc)['radio']['events'][0].update(sequence=999),
                       lambda doc: active(doc)['radio']['pending'].append({'sequence': 3001}),
                       lambda doc: active(doc)['contracts']['states'].append({'state': 'CANCELLED'}),
                       lambda doc: doc['archives'][archive_name(2)]['radio']['evidence'][0].update(sequence=999),
                       lambda doc: doc['files'][archive_name(1)].update(sha256='0' * 64)):
            invalid = copy.deepcopy(self.verify)
            mutate(invalid)
            with self.subTest(mutate=mutate), self.assertRaises((AssertionError, KeyError)):
                validate_phase_journal(invalid, 'verify', self.identities, self.rotate)

    def test_prepare_requires_exact_contract_messages_and_terminal_events(self):
        validate_phase_journal(self.prepare, 'prepare', self.identities, None)
        for mutate in (lambda doc: active(doc)['contracts']['received'][0]['payload'].update(action='cancel'),
                       lambda doc: active(doc)['contracts']['events'][0].update(type='contract.cancel'),
                       lambda doc: active(doc)['contracts']['events'][0].update(actor='provider'),
                       lambda doc: active(doc)['contracts']['events'][1].update(sequence=1002),
                       lambda doc: active(doc)['contracts']['events'][1].update(units=3)):
            invalid = copy.deepcopy(self.prepare)
            mutate(invalid)
            with self.subTest(mutate=mutate), self.assertRaises((AssertionError, KeyError)):
                validate_phase_journal(invalid, 'prepare', self.identities, None)

    def test_shutdown_allows_only_synchronized_clock_progress(self):
        advanced = copy.deepcopy(self.rotate)
        active(advanced)['radio']['tick'] = active(advanced)['contracts']['tick'] = 24
        same_committed_journal(advanced, self.rotate)
        for mutate in (lambda doc: active(doc)['radio'].update(tick=19),
                       lambda doc: active(doc)['contracts'].update(tick=25),
                       lambda doc: active(doc)['radio']['evidence'][0].update(sequence=999),
                       lambda doc: active(doc)['radio']['acknowledged'].pop(),
                       lambda doc: active(doc)['radio']['events'].reverse(),
                       lambda doc: active(doc).update(contractHash='changed'),
                       lambda doc: doc['document'].update(unexpected=True),
                       lambda doc: doc['archives'][archive_name(1)]['contracts']['received'].pop()):
            invalid = copy.deepcopy(advanced)
            mutate(invalid)
            with self.subTest(mutate=mutate), self.assertRaises((AssertionError, KeyError)):
                same_committed_journal(invalid, self.rotate)

    def test_manifest_and_raw_archive_inventory_cannot_lose_or_change_entries(self):
        for mutate in (lambda doc: doc['files'].pop(archive_name(1)),
                       lambda doc: doc['archives'].pop(archive_name(1)),
                       lambda doc: doc['files'].update({'radio-state.json.archives/segment-9999.json': {'bytes': 1, 'sha256': '0' * 64}}),
                       lambda doc: doc['document']['archives'][0].update(bytes=1),
                       lambda doc: doc['document']['archives'][0].update(generation=2),
                       lambda doc: doc['files'][archive_name(1)].update(sha256='0' * 64)):
            invalid = copy.deepcopy(self.rotate)
            mutate(invalid)
            with self.subTest(mutate=mutate), self.assertRaises((AssertionError, KeyError)):
                validate_journal(invalid)

    def test_integer_fields_reject_boolean_and_float_aliases(self):
        mutations = (
            lambda doc: doc['document'].update(schemaVersion=True),
            lambda doc: doc['document'].update(schemaVersion=2.0),
            lambda doc: active(doc).update(schemaVersion=True),
            lambda doc: active(doc).update(schemaVersion=1.0),
            lambda doc: active(doc)['radio'].update(tick=True),
            lambda doc: active(doc)['contracts'].update(tick=20.0),
            lambda doc: doc['document']['archives'][0].update(generation=True),
            lambda doc: doc['document']['archives'][0].update(generation=1.0),
            lambda doc: doc['document']['archives'][0].update(bytes=True),
            lambda doc: doc['document']['archives'][0].update(bytes=float(doc['document']['archives'][0]['bytes'])),
            lambda doc: doc['files'][archive_name(1)].update(bytes=True),
        )
        for mutate in mutations:
            invalid = copy.deepcopy(self.rotate)
            mutate(invalid)
            with self.subTest(mutate=mutate), self.assertRaises((AssertionError, KeyError)):
                validate_journal(invalid)

    def test_real_file_reader_rejects_same_length_archive_tampering_and_missing_bytes(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            (directory / 'radio-state.json').write_text(json.dumps(self.rotate['document']), encoding='utf-8')
            archive = directory / archive_name(1)
            archive.parent.mkdir()
            original = json.dumps(self.rotate['archives'][archive_name(1)]).encode('utf-8')
            archive.write_bytes(original)
            self.assertEqual(read_journal(directory)['files'][archive_name(1)], self.rotate['files'][archive_name(1)])
            archive.write_bytes(original.replace(b'CANCELLED', b'COMPLETED'))
            with self.assertRaises(AssertionError):
                read_journal(directory)
            archive.unlink()
            with self.assertRaises((AssertionError, KeyError)):
                read_journal(directory)

    def test_failed_archive_validation_still_preserves_original_failure_bytes(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            directory = root / 'source'
            directory.mkdir()
            (directory / 'radio-state.json').write_text(json.dumps(self.rotate['document']), encoding='utf-8')
            archive = directory / archive_name(1)
            archive.parent.mkdir()
            archive.write_bytes(b'corrupt archive bytes')
            destination = root / 'failed-evidence'
            with self.assertRaises((AssertionError, ValueError)):
                copy_journal(directory, destination)
            self.assertEqual((destination / archive_name(1)).read_bytes(), archive.read_bytes())
            self.assertEqual((destination / 'radio-state.json').read_bytes(), (directory / 'radio-state.json').read_bytes())

    def test_workflow_requires_fixed_non_operator_identity_and_complete_check_inventory(self):
        checks = ['non_operator_rotation_denied_without_history_changes',
                  'pending_message_blocks_operator_rotation_without_history_changes',
                  'active_contract_blocks_operator_rotation_after_offer_acknowledgement',
                  'all_messages_acknowledged_and_terminal_contract_saved_before_first_restart']
        workflow = {'passed': True, 'phase': 'prepare', 'players': 2, 'checks': checks,
                    'identities': {role: offline_identity(NAMES[role]) for role in ('requester', 'provider')}}
        validate_workflow(workflow, 'prepare', False)
        for mutate in (lambda value: value['checks'].pop(), lambda value: value.update(players=3),
                       lambda value: value.update(passed='false'), lambda value: value.update(passed=1),
                       lambda value: value['identities'].update(provider=value['identities']['requester'])):
            invalid = copy.deepcopy(workflow)
            mutate(invalid)
            with self.subTest(mutate=mutate), self.assertRaises(AssertionError):
                validate_workflow(invalid, 'prepare', False)


if __name__ == '__main__':
    unittest.main()
