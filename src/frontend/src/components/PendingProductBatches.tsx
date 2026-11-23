import { useEffect, useState } from 'react';
import { authenticatedGet } from '../utils/auth';

interface Batch { batchId: string; draft: { accounts: string[]; through: string } }
export function PendingProductBatches() {
  const [page, setPage] = useState(0);
  const [rows, setRows] = useState<Batch[]>([]);
  const [error, setError] = useState('');
  useEffect(() => {
    let active = true;
    authenticatedGet('/api/vulnerability-exception-requests/batches/pending?page=' + page)
      .then(async response => {
        if (!response.ok) throw new Error('Could not load product exception batches');
        const data: Batch[] = await response.json();
        if (active) { setRows(data); setError(''); }
      }).catch(e => { if (active) setError(e.message); });
    return () => { active = false; };
  }, [page]);
  if (!rows.length && !page && !error) return null;
  return <section className="my-3"><h3>Pending product exceptions</h3>{error && <p role="alert">{error}</p>}
    <ul>{rows.map(b => <li key={b.batchId}><a href={'/vulnerabilities/exceptions?batch=' + b.batchId}>Review {b.draft.accounts.length} accounts through {b.draft.through} UTC</a></li>)}</ul>
    {page > 0 && <button onClick={() => setPage(page - 1)}>Previous</button>}
    {rows.length === 25 && <button onClick={() => setPage(page + 1)}>Next</button>}
  </section>;
}
