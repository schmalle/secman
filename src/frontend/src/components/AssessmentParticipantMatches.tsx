interface Props {
  query: string;
  users: { id: number | null; username: string; email: string }[];
  selectedRef: string;
  loading: boolean;
  error: string;
  label: string;
  hidden?: boolean;
  onSelect: (id: number) => void;
}

export default function AssessmentParticipantMatches({ query, users, selectedRef, loading, error, label, hidden, onSelect }: Props) {
  if (hidden || !query.trim()) return null;
  if (loading) return <p role="status" className="text-muted">Searching users…</p>;
  if (error) return <p role="alert" className="text-danger">{error}</p>;
  const matches = users.filter(user => user.id != null);
  if (!matches.length) return <p role="status" className="text-muted">No matching users found.</p>;
  return <div className="mb-2">
    <p role="status" className="form-text mb-1">{matches.length} matching users. Select a user below.</p>
    <ul className="list-group" aria-label={label} style={{ maxHeight: '240px', overflowY: 'auto' }}>
      {matches.map(user => <li className="list-group-item p-0" key={user.id}>
        <button type="button" className={`list-group-item list-group-item-action border-0${selectedRef === `id:${user.id}` ? ' active' : ''}`}
          aria-pressed={selectedRef === `id:${user.id}`} onClick={() => onSelect(user.id!)}>
          {user.username} ({user.email})
        </button>
      </li>)}
    </ul>
  </div>;
}
