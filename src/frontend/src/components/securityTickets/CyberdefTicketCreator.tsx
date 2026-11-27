import React, { useEffect, useState } from 'react';
import BulkTicketImport from './BulkTicketImport';
import TicketCreateForm from './TicketCreateForm';
import type { TicketMeta } from '../../services/securityTicketLogic';
import { securityTicketService } from '../../services/securityTicketService';
import { getUser } from '../../utils/auth';

/**
 * The /cyberdef/new page: one ticket or a bulk import. The role check here is only for the
 * UI; the backend refuses submissions from anyone but CYBERDEF and ADMIN.
 */
const CyberdefTicketCreator: React.FC = () => {
  const [tab, setTab] = useState<'single' | 'bulk'>('single');
  const [meta, setMeta] = useState<TicketMeta | null>(null);
  const [allowed, setAllowed] = useState<boolean | null>(null);

  useEffect(() => {
    const roles = getUser()?.roles ?? [];
    const mayCreate = roles.includes('CYBERDEF') || roles.includes('ADMIN');
    setAllowed(mayCreate);
    if (mayCreate) securityTicketService.meta().then(result => { if (result.ok) setMeta(result.data); });
  }, []);

  if (allowed === null) return null;
  if (!allowed) {
    return <div className="alert alert-warning">Only Cyber Defence (CYBERDEF) and administrators can create security tickets.</div>;
  }
  return (
    <div className="container-fluid py-3">
      <div className="d-flex justify-content-between align-items-center mb-3">
        <h2 className="h4 mb-0"><i className="bi bi-shield-plus me-2"></i>New security ticket</h2>
        <a href="/security-tickets" className="btn btn-outline-secondary btn-sm">All tickets</a>
      </div>
      <ul className="nav nav-tabs mb-3">
        <li className="nav-item">
          <button type="button" className={`nav-link${tab === 'single' ? ' active' : ''}`} onClick={() => setTab('single')}>Single ticket</button>
        </li>
        <li className="nav-item">
          <button type="button" className={`nav-link${tab === 'bulk' ? ' active' : ''}`} onClick={() => setTab('bulk')}>Bulk import</button>
        </li>
      </ul>
      {tab === 'single' ? <TicketCreateForm meta={meta} /> : <BulkTicketImport />}
    </div>
  );
};

export default CyberdefTicketCreator;
