import { strict as assert } from 'node:assert';
import { describe, it } from 'node:test';

import {
    COPYRIGHT_START_YEAR,
    UNKNOWN_LABEL,
    formatBuildStamp,
    formatBuildTimestamp,
    formatCommitTooltip,
    formatCopyrightRange,
    normalizeBuildInfo,
    normalizeCommitHash,
    normalizeTimestamp,
} from './buildInfo';

describe('normalizeTimestamp', () => {
    it('canonicalises an offset timestamp to UTC', () => {
        // git %cI emits the committer's local offset; the footer must not vary
        // with the build machine's timezone.
        assert.equal(normalizeTimestamp('2026-08-11T22:18:51+02:00'), '2026-08-11T20:18:51.000Z');
    });

    it('passes a UTC timestamp through unchanged', () => {
        assert.equal(normalizeTimestamp('2026-08-11T20:18:51.000Z'), '2026-08-11T20:18:51.000Z');
    });

    it('rejects values that are not usable timestamps', () => {
        for (const value of [null, undefined, 42, {}, '', '   ', 'not-a-date', 'x'.repeat(65)]) {
            assert.equal(normalizeTimestamp(value), null, `expected null for ${JSON.stringify(value)}`);
        }
    });
});

describe('normalizeCommitHash', () => {
    it('accepts abbreviated and full object names, lowercased', () => {
        assert.equal(normalizeCommitHash('29196e7'), '29196e7');
        assert.equal(normalizeCommitHash('29196E7ABCDEF'), '29196e7abcdef');
        assert.equal(normalizeCommitHash(' 29196e7 '), '29196e7');
    });

    it('rejects anything that is not a plausible hash', () => {
        // The value reaches the rendered page; only hex of a sane length gets through.
        for (const value of [null, undefined, 12345, '', 'abc', 'zzzzzzz', '0'.repeat(41), '<script>']) {
            assert.equal(normalizeCommitHash(value), null, `expected null for ${JSON.stringify(value)}`);
        }
    });
});

describe('normalizeBuildInfo', () => {
    it('parses the JSON string injected by vite define', () => {
        const info = normalizeBuildInfo(
            JSON.stringify({
                buildTime: '2026-08-11T20:30:00Z',
                commitTime: '2026-08-11T22:18:51+02:00',
                commitHash: '29196e7',
            }),
        );

        assert.deepEqual(info, {
            buildTime: '2026-08-11T20:30:00.000Z',
            commitTime: '2026-08-11T20:18:51.000Z',
            commitHash: '29196e7',
        });
    });

    it('accepts an already-parsed object', () => {
        const info = normalizeBuildInfo({ buildTime: '2026-01-02T03:04:05Z' });

        assert.equal(info.buildTime, '2026-01-02T03:04:05.000Z');
        assert.equal(info.commitTime, null);
        assert.equal(info.commitHash, null);
    });

    it('degrades to all-unknown rather than to a wrong date', () => {
        // Injection missing entirely, or malformed — the failure mode that used
        // to surface as a hardcoded 2025-06-08 in every container.
        for (const value of [null, undefined, 'not json', '[]', 7, { buildTime: 'nonsense' }]) {
            const info = normalizeBuildInfo(value);
            assert.deepEqual(
                info,
                { buildTime: null, commitTime: null, commitHash: null },
                `expected empty build info for ${JSON.stringify(value)}`,
            );
        }
    });
});

describe('formatBuildTimestamp', () => {
    it('renders minute precision in UTC', () => {
        assert.equal(formatBuildTimestamp('2026-08-11T20:18:51.000Z'), '2026-08-11 20:18 UTC');
    });

    it('renders unknown when the stamp is missing', () => {
        assert.equal(formatBuildTimestamp(null), UNKNOWN_LABEL);
    });
});

describe('formatBuildStamp', () => {
    it('appends the commit when the build had git context', () => {
        assert.equal(
            formatBuildStamp({
                buildTime: '2026-08-11T20:18:51.000Z',
                commitTime: '2026-08-11T20:18:51.000Z',
                commitHash: '29196e7',
            }),
            '2026-08-11 20:18 UTC (29196e7)',
        );
    });

    it('omits the commit when there is none', () => {
        assert.equal(
            formatBuildStamp({ buildTime: '2026-08-11T20:18:51.000Z', commitTime: null, commitHash: null }),
            '2026-08-11 20:18 UTC',
        );
    });

    it('reports unknown when even the build time is missing', () => {
        assert.equal(
            formatBuildStamp({ buildTime: null, commitTime: null, commitHash: null }),
            UNKNOWN_LABEL,
        );
    });
});

describe('formatCommitTooltip', () => {
    it('describes the commit date, which is not the build date', () => {
        assert.equal(
            formatCommitTooltip({
                buildTime: '2026-08-12T09:00:00.000Z',
                commitTime: '2026-08-11T20:18:51.000Z',
                commitHash: '29196e7',
            }),
            'Commit 29196e7 committed 2026-08-11 20:18 UTC',
        );
    });

    it('is absent without a commit time', () => {
        assert.equal(
            formatCommitTooltip({ buildTime: '2026-08-12T09:00:00.000Z', commitTime: null, commitHash: null }),
            undefined,
        );
    });
});

describe('formatCopyrightRange', () => {
    it('ends at the build year instead of a hardcoded literal', () => {
        assert.equal(formatCopyrightRange('2026-08-11T20:18:51.000Z'), `${COPYRIGHT_START_YEAR}–2026`);
        assert.equal(formatCopyrightRange('2031-01-01T00:00:00.000Z'), `${COPYRIGHT_START_YEAR}–2031`);
    });

    it('collapses to a single year when the build is not after the start year', () => {
        assert.equal(formatCopyrightRange('2017-05-01T00:00:00.000Z'), String(COPYRIGHT_START_YEAR));
        assert.equal(formatCopyrightRange('2010-05-01T00:00:00.000Z'), String(COPYRIGHT_START_YEAR));
    });

    it('falls back to the start year when the build time is unknown', () => {
        assert.equal(formatCopyrightRange(null), String(COPYRIGHT_START_YEAR));
    });
});
