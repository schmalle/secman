import React, { useEffect, useRef, useState } from 'react';
import type { AccountOption } from '../../services/securityTicketLogic';
import { securityTicketService } from '../../services/securityTicketService';

interface Props {
  value: string;
  onChange: (accountId: string, option: AccountOption | null) => void;
  invalid?: boolean;
}

/**
 * Type-ahead over known AWS accounts (id or name). Shows how many people a ticket would
 * reach, so an unowned account is visible before submitting. A typed 12-digit id that is
 * not in the list is still accepted; the server decides whether it is routable.
 */
const AccountPicker: React.FC<Props> = ({ value, onChange, invalid }) => {
  const [options, setOptions] = useState<AccountOption[]>([]);
  const [open, setOpen] = useState(false);
  const [selected, setSelected] = useState<AccountOption | null>(null);
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);

  useEffect(() => {
    clearTimeout(timer.current);
    if (!open) return;
    timer.current = setTimeout(async () => {
      const result = await securityTicketService.accounts(value.trim());
      setOptions(result.ok ? result.data : []);
    }, 250);
    return () => clearTimeout(timer.current);
  }, [value, open]);

  const choose = (option: AccountOption) => {
    setSelected(option);
    setOpen(false);
    onChange(option.awsAccountId, option);
  };

  return (
    <div className="position-relative">
      <input
        id="ticket-account" className={`form-control${invalid ? ' is-invalid' : ''}`} autoComplete="off"
        placeholder="12-digit account id or account name" value={value}
        onFocus={() => setOpen(true)} onBlur={() => setTimeout(() => setOpen(false), 150)}
        onChange={e => { setSelected(null); onChange(e.target.value, null); setOpen(true); }}
      />
      {open && options.length > 0 && (
        <ul className="list-group position-absolute w-100 shadow-sm" style={{ zIndex: 20, maxHeight: 280, overflowY: 'auto' }}>
          {options.map(option => (
            <li key={option.awsAccountId}>
              <button type="button" className="list-group-item list-group-item-action d-flex justify-content-between"
                onMouseDown={e => e.preventDefault()} onClick={() => choose(option)}>
                <span><code>{option.awsAccountId}</code> {option.accountName && <span className="text-muted">· {option.accountName}</span>}</span>
                <RoutingBadge option={option} />
              </button>
            </li>
          ))}
        </ul>
      )}
      {selected && <div className="form-text">{selected.accountName ?? 'Unnamed account'} · <RoutingBadge option={selected} /></div>}
    </div>
  );
};

/** "3 recipients" in green, or "no owner" in amber. */
export const RoutingBadge: React.FC<{ option: AccountOption }> = ({ option }) => (
  option.routable
    ? <span className="badge bg-success">{option.recipientCount} recipient{option.recipientCount === 1 ? '' : 's'}</span>
    : <span className="badge bg-warning text-dark">no owner</span>
);

export default AccountPicker;
