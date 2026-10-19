import React, { useState } from 'react';
import type { BuddyInventoryInput } from '../services/aiBuddyService';

export default function AiBuddyInventoryForm({ busy, onCreate }: { busy: boolean; onCreate: (inventory: BuddyInventoryInput) => void }) {
  const [input, setInput] = useState<BuddyInventoryInput>({ build: '', edition: '', architecture: '', firefoxVersion: '', firefoxChannel: '' });
  const [confirmed, setConfirmed] = useState(false);
  const update = (key: keyof BuddyInventoryInput, value: string) => { setInput(previous => ({ ...previous, [key]: value })); setConfirmed(false); };
  return <details className="my-3">
    <summary>Complete missing inventory and create a new guide</summary>
    <p className="mt-2">Enter only facts checked on this system. They apply to this temporary guide and do not change saved inventory or prove patch applicability. Leave unknown fields blank.</p>
    <form onSubmit={event => { event.preventDefault(); if (confirmed) onCreate(input); }}>
      <fieldset disabled={busy}>
        <div className="row g-2">
          <label className="col-md-4">Windows full build<input className="form-control" value={input.build} onChange={event => update('build', event.target.value)} pattern="[0-9]{5}\.[0-9]{1,6}" maxLength={12} placeholder="Example: 17763.1234" /></label>
          <label className="col-md-4">Windows edition<select className="form-select" value={input.edition} onChange={event => update('edition', event.target.value)}><option value="">Unknown</option><option>Standard</option><option>Datacenter</option></select></label>
          <label className="col-md-4">Architecture<select className="form-select" value={input.architecture} onChange={event => update('architecture', event.target.value)}><option value="">Unknown</option><option>x64</option><option>x86</option><option>ARM64</option></select></label>
          <label className="col-md-6">Firefox version<input className="form-control" value={input.firefoxVersion} onChange={event => update('firefoxVersion', event.target.value)} pattern="[0-9]{1,8}(\.[0-9]{1,8}){0,5}" maxLength={53} required={Boolean(input.firefoxChannel)} placeholder="Exact numeric version from Help → About Firefox" /></label>
          <label className="col-md-6">Firefox channel<select className="form-select" value={input.firefoxChannel} onChange={event => update('firefoxChannel', event.target.value)} required={Boolean(input.firefoxVersion)}><option value="">Unknown</option><option value="RELEASE">Release</option><option value="ESR">ESR</option></select></label>
        </div>
        <label className="d-block my-2"><input type="checkbox" checked={confirmed} onChange={event => setConfirmed(event.target.checked)} required /> I checked these facts on the affected system.</label>
        <button className="btn btn-primary" type="submit" disabled={!confirmed || !Object.values(input).some(Boolean)}>Create guide with these facts</button>
      </fieldset>
    </form>
  </details>;
}
