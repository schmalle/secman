import assert from 'node:assert/strict';
import test, { beforeEach } from 'node:test';
import {
  downloadBlob,
  downloadResponse,
  filenameFromContentDisposition,
} from './download.ts';

// `downloadBlob` is the one place in the app that touches the DOM to save a file, so
// what it does to the anchor and to the object URL is the contract worth pinning:
// the click must happen while the node is still attached (Firefox drops clicks on
// detached nodes), and the URL must always be revoked (a leaked one pins the blob's
// bytes until the page unloads). Both were wrong in some of the copies this replaced.

interface FakeAnchor {
  href: string;
  download: string;
  style: { display: string };
  clickedWhileAttached: boolean | null;
  remove(): void;
  click(): void;
}

let anchors: FakeAnchor[];
let createdUrls: string[];
let revokedUrls: string[];
let attached: FakeAnchor[];

function installDomStubs(): void {
  anchors = [];
  createdUrls = [];
  revokedUrls = [];
  attached = [];

  (globalThis as any).document = {
    createElement(tag: string) {
      assert.equal(tag, 'a');
      const anchor: FakeAnchor = {
        href: '',
        download: '',
        style: { display: '' },
        clickedWhileAttached: null,
        remove() {
          const i = attached.indexOf(anchor);
          if (i >= 0) attached.splice(i, 1);
        },
        click() {
          anchor.clickedWhileAttached = attached.includes(anchor);
        },
      };
      anchors.push(anchor);
      return anchor;
    },
    body: {
      appendChild(node: FakeAnchor) {
        attached.push(node);
      },
    },
  };

  (globalThis as any).URL = {
    createObjectURL(_blob: unknown) {
      const url = `blob:stub/${createdUrls.length}`;
      createdUrls.push(url);
      return url;
    },
    revokeObjectURL(url: string) {
      revokedUrls.push(url);
    },
  };
}

beforeEach(installDomStubs);

test('downloadBlob clicks an attached anchor and then detaches it', () => {
  downloadBlob(new Blob(['x']), 'report.xlsx');

  assert.equal(anchors.length, 1);
  assert.equal(anchors[0].download, 'report.xlsx');
  assert.equal(anchors[0].href, createdUrls[0]);
  assert.equal(anchors[0].clickedWhileAttached, true);
  assert.deepEqual(attached, [], 'anchor must not be left in the document');
});

test('downloadBlob revokes the object URL, including when the click throws', () => {
  downloadBlob(new Blob(['x']), 'a.txt');
  assert.deepEqual(revokedUrls, createdUrls);

  const original = (globalThis as any).document.createElement;
  (globalThis as any).document.createElement = (tag: string) => {
    const anchor = original(tag);
    anchor.click = () => {
      throw new Error('popup blocked');
    };
    return anchor;
  };

  assert.throws(() => downloadBlob(new Blob(['y']), 'b.txt'), /popup blocked/);
  assert.deepEqual(revokedUrls, createdUrls, 'a failed click must still revoke the URL');
});

test('downloadBlob substitutes a name when the caller supplies an empty one', () => {
  downloadBlob(new Blob(['x']), '');
  assert.equal(anchors[0].download, 'download');
});

test('a quoted filename is read out of Content-Disposition', () => {
  assert.equal(
    filenameFromContentDisposition('attachment; filename="Top 50 Assets.xlsx"', 'fallback.xlsx'),
    'Top 50 Assets.xlsx',
  );
});

test('an unquoted filename is read out of Content-Disposition', () => {
  // The /filename="([^"]+)"/ spelling this replaced missed this form entirely and
  // silently fell back to the caller's default.
  assert.equal(
    filenameFromContentDisposition('attachment; filename=export.csv', 'fallback.csv'),
    'export.csv',
  );
});

test('a bare filename stops at the next parameter rather than swallowing it', () => {
  // The greedy /filename="(.+)"/ spelling returned `a.csv"; charset="utf-8` here.
  assert.equal(
    filenameFromContentDisposition('attachment; filename="a.csv"; charset="utf-8"', 'f.csv'),
    'a.csv',
  );
  assert.equal(
    filenameFromContentDisposition('attachment; filename=a.csv; size=12', 'f.csv'),
    'a.csv',
  );
});

test('the RFC 5987 extended form wins over the plain form and is percent-decoded', () => {
  assert.equal(
    filenameFromContentDisposition(
      `attachment; filename="fallback.xlsx"; filename*=UTF-8''report%20Q1%20%E2%82%AC.xlsx`,
      'f.xlsx',
    ),
    'report Q1 €.xlsx',
  );
});

test('a malformed percent sequence is passed through instead of throwing', () => {
  assert.equal(
    filenameFromContentDisposition(`attachment; filename*=UTF-8''bad%ZZ.csv`, 'f.csv'),
    'bad%ZZ.csv',
  );
});

test('the header name is matched case-insensitively', () => {
  assert.equal(
    filenameFromContentDisposition('attachment; FileName="Report.docx"', 'f.docx'),
    'Report.docx',
  );
});

test('a directory component in the header is stripped', () => {
  // The header is server-controlled input. Browsers already refuse a path in
  // `a.download`, but the parsed value is reduced to its basename so it is safe
  // for any other use a caller puts it to.
  assert.equal(
    filenameFromContentDisposition('attachment; filename="../../etc/passwd"', 'f.txt'),
    'passwd',
  );
  assert.equal(
    filenameFromContentDisposition('attachment; filename="C:\\\\temp\\\\evil.exe"', 'f.txt'),
    'evil.exe',
  );
});

test('a missing, empty or filename-less header yields the fallback', () => {
  assert.equal(filenameFromContentDisposition(null, 'fallback.xlsx'), 'fallback.xlsx');
  assert.equal(filenameFromContentDisposition(undefined, 'fallback.xlsx'), 'fallback.xlsx');
  assert.equal(filenameFromContentDisposition('', 'fallback.xlsx'), 'fallback.xlsx');
  assert.equal(filenameFromContentDisposition('attachment', 'fallback.xlsx'), 'fallback.xlsx');
  assert.equal(filenameFromContentDisposition('attachment; filename=""', 'fallback.xlsx'), 'fallback.xlsx');
  assert.equal(filenameFromContentDisposition('attachment; filename=".."', 'fallback.xlsx'), 'fallback.xlsx');
});

test('downloadResponse names the file from the response header', async () => {
  const response = new Response('body', {
    headers: { 'Content-Disposition': 'attachment; filename="named.json"' },
  });
  await downloadResponse(response, 'fallback.json');
  assert.equal(anchors[0].download, 'named.json');
});

test('downloadResponse falls back when the response carries no header', async () => {
  await downloadResponse(new Response('body'), 'fallback.json');
  assert.equal(anchors[0].download, 'fallback.json');
});
