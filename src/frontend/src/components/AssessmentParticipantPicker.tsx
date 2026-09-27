import { useState } from 'react';

interface Props {
  id: string;
  label: string;
  query: string;
  users: { id: number | null; username: string; email: string }[];
  selectedRef: string;
  loading: boolean;
  error: string;
  required?: boolean;
  allowEmail?: boolean;
  onQueryChange: (query: string) => void;
  onSelect: (ref: string) => void;
}

export default function AssessmentParticipantPicker({ id, label, query, users, selectedRef, loading, error, required, allowEmail, onQueryChange, onSelect }: Props) {
  const [open, setOpen] = useState(false);
  const [activeIndex, setActiveIndex] = useState(-1);
  const selected = users.find(user => `id:${user.id}` === selectedRef);
  const selectedLabel = selected ? `${selected.username} (${selected.email})` : selectedRef.startsWith('email:') ? selectedRef.slice(6) : query;
  const email = query.trim();
  const choices = users.filter(user => user.id != null).map(user => ({
    ref: `id:${user.id}`, name: user.username, detail: user.email,
  }));
  if (allowEmail && /^[^\s@,;:<>"\\]+@[^\s@,;:<>"\\]+\.[^\s@,;:<>"\\]+$/.test(email) &&
      !users.some(user => user.email.toLowerCase() === email.toLowerCase())) {
    choices.push({ ref: `email:${email}`, name: `Invite ${email}`, detail: 'No SecMan account needed' });
  }
  const choose = (ref: string) => { onSelect(ref); setOpen(false); setActiveIndex(-1); };
  const available = !loading && !error;
  return <div onBlur={event => { if (!event.currentTarget.contains(event.relatedTarget)) setOpen(false); }}>
    <label className="form-label" htmlFor={id}>{label}{required ? ' *' : ' (optional)'}</label>
    <div className="position-relative">
      <input id={id} className="form-control pe-5" role="combobox" autoComplete="off" maxLength={255}
        aria-expanded={open} aria-controls={`${id}-options`} aria-autocomplete="list"
        aria-describedby={`${id}-help`} aria-required={required}
        aria-activedescendant={open && available && activeIndex >= 0 && choices[activeIndex] ? `${id}-option-${activeIndex}` : undefined}
        placeholder={allowEmail ? 'Search a name or enter an email' : 'Search a name or email'}
        value={!open && selectedRef ? selectedLabel : query}
        onFocus={() => setOpen(true)}
        onClick={() => setOpen(true)}
        onChange={event => { onQueryChange(event.target.value); setOpen(true); setActiveIndex(-1); }}
        onKeyDown={event => {
          if (event.key === 'Escape') { setOpen(false); return; }
          if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
            event.preventDefault(); setOpen(true);
            if (available) setActiveIndex(index => Math.max(0, Math.min(choices.length - 1, index + (event.key === 'ArrowDown' ? 1 : -1))));
          }
          if (event.key === 'Enter' && open) {
            event.preventDefault();
            if (available && choices[activeIndex]) choose(choices[activeIndex].ref);
          }
        }} />
      {(query || selectedRef) && <button type="button" className="btn position-absolute top-0 end-0" aria-label={`Clear ${label.toLowerCase()}`}
        onClick={() => { onQueryChange(''); setActiveIndex(-1); setOpen(false); }}>×</button>}
      {open && <div className="position-absolute w-100 bg-white border rounded shadow-sm mt-1" style={{ zIndex: 1050, maxHeight: '240px', overflowY: 'auto' }}>
        {loading ? <div className="p-2 text-muted" role="status">Searching…</div> : error ? <div className="p-2 text-danger" role="alert">{error}</div> :
          choices.length === 0 && <div className="p-2 text-muted" role="status">{allowEmail ? 'No matches. Enter a full email to invite someone.' : 'No matching users.'}</div>}
        <ul id={`${id}-options`} role="listbox" aria-label={`${label} matches`} className="list-unstyled mb-0">
          {available && choices.map((choice, index) => <li id={`${id}-option-${index}`} key={choice.ref} role="option"
            aria-selected={selectedRef === choice.ref} className={`px-3 py-2 ${activeIndex === index ? 'bg-primary text-white' : ''}`}
            style={{ cursor: 'pointer' }} onMouseDown={event => event.preventDefault()} onMouseMove={() => setActiveIndex(index)} onClick={() => choose(choice.ref)}>
            <div>{choice.name}</div><small className={activeIndex === index ? '' : 'text-muted'}>{choice.detail}</small>
          </li>)}
        </ul>
      </div>}
    </div>
    <div id={`${id}-help`} className="form-text">{allowEmail ? 'Choose a user or invite someone by email. No registration needed.' : 'Choose an existing SecMan user.'}</div>
  </div>;
}
