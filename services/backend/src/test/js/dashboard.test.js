'use strict';

const assert = require('node:assert/strict');
const {
  normalizeEpisodeEvent,
  mergeEpisodeState,
  canAcknowledge,
} = require('../../main/resources/static/js/dashboard.js');

const flat = normalizeEpisodeEvent({
  id: 1, status: 'OPEN', currentSeverity: 'WARNING',
});
assert.equal(flat.episode.id, 1);
assert.equal(flat.episode.status, 'OPEN');

const wrapped = normalizeEpisodeEvent({
  changeType: 'RESOLVED',
  episode: { id: 1, resolvedAt: '2026-07-23T01:00:00Z' },
});
assert.equal(wrapped.episode.status, 'RESOLVED');

const resolved = {
  id: 1, status: 'RESOLVED', updatedAt: '2026-07-23T01:00:00Z',
  resolutionReason: 'RECOVERED',
};
const lateOpen = {
  id: 1, status: 'OPEN', updatedAt: '2026-07-23T00:59:00Z',
};
assert.deepEqual(mergeEpisodeState(resolved, lateOpen), resolved);

const ack2 = {
  id: 2, status: 'OPEN', updatedAt: '2026-07-23T01:00:00Z',
  latestAcknowledgement: { createdAt: '2026-07-23T01:00:02Z', ackSeverity: 'CRITICAL' },
};
const lateAck1 = {
  id: 2, status: 'OPEN', updatedAt: '2026-07-23T01:00:00Z',
  latestAcknowledgement: { createdAt: '2026-07-23T01:00:01Z', ackSeverity: 'WARNING' },
};
assert.deepEqual(mergeEpisodeState(ack2, lateAck1), ack2);

const acknowledgedOpen = {
  id: 3, status: 'OPEN', updatedAt: '2026-07-23T01:00:00Z',
  latestAcknowledgement: { createdAt: '2026-07-23T01:00:02Z', ackSeverity: 'WARNING' },
};
const resolvedWithoutAck = {
  id: 3, status: 'RESOLVED', updatedAt: '2026-07-23T01:00:03Z',
  resolvedAt: '2026-07-23T01:00:03Z', resolutionReason: 'RECOVERED',
};
const resolvedWithAck = mergeEpisodeState(acknowledgedOpen, resolvedWithoutAck);
assert.equal(resolvedWithAck.status, 'RESOLVED');
assert.equal(resolvedWithAck.latestAcknowledgement.createdAt, '2026-07-23T01:00:02Z');

const enrichedWithoutAck = {
  id: 3, status: 'OPEN', updatedAt: '2026-07-23T01:00:04Z',
  evidence: 'new evidence', recommendation: 'new recommendation',
};
const enrichedWithAck = mergeEpisodeState(acknowledgedOpen, enrichedWithoutAck);
assert.equal(enrichedWithAck.evidence, 'new evidence');
assert.equal(enrichedWithAck.latestAcknowledgement.createdAt, '2026-07-23T01:00:02Z');

global.Auth = { getRole: () => 'SYSTEM_VIEWER' };
assert.equal(canAcknowledge({ status: 'OPEN', currentSeverity: 'WARNING' }), false);
global.Auth = { getRole: () => 'MEMBER' };
assert.equal(canAcknowledge({ status: 'OPEN', currentSeverity: 'WARNING' }), true);
delete global.Auth;

console.log('dashboard episode tests passed');
