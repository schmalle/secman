import React, { useEffect, useState } from 'react';
import { dueText, SEVERITY_BADGE, type TicketStatus } from '../../services/securityTicketLogic';
import { securityTicketService, type TicketCounts, type TicketPage } from '../../services/securityTicketService';
import { getUser } from '../../utils/auth';

type Tab = 'OPEN' | 'CLOSED' | 'ALL';

/**
 * Tickets the caller can see: their accounts' tickets, or all of them for CYBERDEF, ADMIN
 * and SECCHAMPION. The server does the scoping; this page only filters and pages.
 */
const SecurityTicketList: React.FC = () => {
  const [tab, setTab] = useState<Tab>('OPEN');
  const [query, setQuery] = useState('');
  const [page, setPage] = useState(0);
  const [data, setData] = useState<TicketPage | null>(null);
  const [counts, setCounts] = useState<TicketCounts | null>(null);
  const [failure, setFailure] = useState<string | null>(null);
  const roles = getUser()?.roles ?? [];
  const mayCreate = roles.includes('CYBERDEF') || roles.includes('ADMIN');

  useEffect(() => { securityTicketService.summary().then(r => { if (r.ok) setCounts(r.data); }); }, []);

  useEffect(() => {
    const handle = setTimeout(async () => {
      const status: TicketStatus | undefined = tab === 'ALL' ? undefined : tab;
      const result = await securityTicketService.list({ status, q: query.trim() || undefined, page });
      if (result.ok) { setData(result.data); setFailure(null); } else setFailure(result.error.message);
    }, 250);
    return () => clearTimeout(handle);
  }, [tab, query, page]);

  return (
    <div className="container-fluid py-3">
      <div className="d-flex justify-content-between align-items-center mb-3">
        <h2 className="h4 mb-0"><i className="bi bi-shield-exclamation me-2"></i>Security tickets</h2>
        {mayCreate && <a href="/cyberdef/new" className="btn btn-primary btn-sm"><i className="bi bi-plus-lg me-1"></i>New ticket</a>}
      </div>
      {counts && <CountTiles counts={counts} />}
      <div className="d-flex flex-wrap gap-2 align-items-center mb-3">
        <div className="btn-group btn-group-sm" role="group" aria-label="Status">
          {(['OPEN', 'CLOSED', 'ALL'] as Tab[]).map(t => (
            <button key={t} type="button" className={`btn ${tab === t ? 'btn-secondary' : 'btn-outline-secondary'}`}
              onClick={() => { setTab(t); setPage(0); }}>{t === 'ALL' ? 'All' : t === 'OPEN' ? 'Open' : 'Closed'}</button>
          ))}
        </div>
        <input className="form-control form-control-sm w-auto" placeholder="Search title or CD-key" aria-label="Search"
          value={query} onChange={e => { setQuery(e.target.value); setPage(0); }} />
      </div>
      {failure && <div className="alert alert-danger">{failure}</div>}
      {data && <TicketTable data={data} onPage={setPage} />}
    </div>
  );
};

const CountTiles: React.FC<{ counts: TicketCounts }> = ({ counts }) => (
  <div className="row g-3 mb-3">
    {[['Open', counts.open, 'text-primary'], ['Overdue', counts.overdue, 'text-danger'], ['Closed', counts.closed, 'text-success']]
      .map(([label, value, color]) => (
        <div key={label as string} className="col-6 col-md-3">
          <div className="card shadow-sm"><div className="card-body py-2">
            <div className="small text-muted">{label}</div><div className={`fs-4 fw-semibold ${color}`}>{value}</div>
          </div></div>
        </div>
      ))}
  </div>
);

const TicketTable: React.FC<{ data: TicketPage; onPage: (page: number) => void }> = ({ data, onPage }) => {
  if (data.content.length === 0) return <div className="text-muted py-4 text-center">No security tickets here.</div>;
  return (
    <>
      <div className="table-responsive card shadow-sm">
        <table className="table table-hover table-sm mb-0 align-middle">
          <thead><tr><th>Ticket</th><th>Severity</th><th>Title</th><th>Account</th><th>Status</th><th>Due</th><th>Updated</th></tr></thead>
          <tbody>
            {data.content.map(t => (
              <tr key={t.id} style={{ cursor: 'pointer' }} onClick={() => { window.location.href = `/security-tickets/${t.id}`; }}>
                <td><a href={`/security-tickets/${t.id}`}>{t.ticketKey}</a></td>
                <td><span className={`badge ${SEVERITY_BADGE[t.severity] ?? 'bg-secondary'}`}>{t.severity}</span></td>
                <td>{t.title}</td>
                <td><code>{t.awsAccountId}</code>{t.awsAccountName && <div className="small text-muted">{t.awsAccountName}</div>}</td>
                <td><span className={`badge ${t.status === 'OPEN' ? 'bg-primary' : 'bg-success'}`}>{t.status}</span></td>
                <td className={t.overdue ? 'text-danger fw-semibold' : ''}>{dueText(t.dueAt, t.status)}</td>
                <td className="small text-muted">{new Date(t.updatedAt).toLocaleString()}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {data.totalPages > 1 && (
        <div className="d-flex justify-content-end gap-2 mt-2">
          <button type="button" className="btn btn-sm btn-outline-secondary" disabled={data.page === 0} onClick={() => onPage(data.page - 1)}>Previous</button>
          <span className="small align-self-center">Page {data.page + 1} of {data.totalPages}</span>
          <button type="button" className="btn btn-sm btn-outline-secondary" disabled={data.page + 1 >= data.totalPages} onClick={() => onPage(data.page + 1)}>Next</button>
        </div>
      )}
    </>
  );
};

export default SecurityTicketList;
