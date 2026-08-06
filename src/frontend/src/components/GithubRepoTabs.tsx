import React, { useState } from 'react';
import GithubRepoManagement from './GithubRepoManagement';
import GithubOwnerEmailMappings from './GithubOwnerEmailMappings';

type Tab = 'repositories' | 'owner-email-mappings';

/** Tab shell for the GitHub repos page: repository inventory + owner email mappings. */
const GithubRepoTabs: React.FC = () => {
  const [tab, setTab] = useState<Tab>('repositories');

  return (
    <div>
      <ul className="nav nav-tabs mb-3">
        <li className="nav-item">
          <button
            className={`nav-link ${tab === 'repositories' ? 'active' : ''}`}
            onClick={() => setTab('repositories')}
          >
            Repositories
          </button>
        </li>
        <li className="nav-item">
          <button
            className={`nav-link ${tab === 'owner-email-mappings' ? 'active' : ''}`}
            onClick={() => setTab('owner-email-mappings')}
          >
            Owner email mappings
          </button>
        </li>
      </ul>
      {tab === 'repositories' ? <GithubRepoManagement /> : (
        <div className="container-fluid pb-4">
          <GithubOwnerEmailMappings />
        </div>
      )}
    </div>
  );
};

export default GithubRepoTabs;
