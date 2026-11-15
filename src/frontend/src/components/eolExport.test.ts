import assert from 'node:assert/strict';
import test from 'node:test';
import { loadEolExportFindings } from './eolExport.ts';
import type { EolFinding, EolFindingList } from '../services/eolService';

function page(index: number, total: number): EolFindingList {
  return {
    findings: [{ id: index } as EolFinding],
    total,
    page: index,
    pageSize: 500,
  };
}

test('export loads pages in order with at most four requests in flight', async () => {
  const requested: number[] = [];
  let active = 0;
  let peak = 0;
  const findings = await loadEolExportFindings(async (index, size) => {
    assert.equal(size, 500);
    requested.push(index);
    active += 1;
    peak = Math.max(peak, active);
    await new Promise((resolve) => setTimeout(resolve, (index % 4) * 2));
    active -= 1;
    return page(index, 4500);
  });

  assert.deepEqual(requested, [0, 1, 2, 3, 4, 5, 6, 7, 8]);
  assert.deepEqual(findings.map((finding) => finding.id), requested);
  assert.equal(peak, 4);
});

test('export stops on an empty page and propagates request failures', async () => {
  const requested: number[] = [];
  const findings = await loadEolExportFindings(async (index) => {
    requested.push(index);
    return index === 2 ? { ...page(index, 5000), findings: [] } : page(index, 5000);
  });
  assert.deepEqual(findings.map((finding) => finding.id), [0, 1]);
  assert.deepEqual(requested, [0, 1, 2, 3, 4]);

  await assert.rejects(
    loadEolExportFindings(async (index) => {
      if (index === 2) throw new Error('page failed');
      return page(index, 1500);
    }),
    /page failed/,
  );
});

test('export limits a stale total to 200 pages', async () => {
  let requests = 0;
  await loadEolExportFindings(async (index) => {
    requests += 1;
    return page(index, 1000000);
  });
  assert.equal(requests, 200);
});
