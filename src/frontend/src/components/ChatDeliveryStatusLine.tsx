import React from 'react';
import { formatServerDateTime } from '../utils/dateUtils';

/**
 * "Last delivery attempt: … — SENT/FAILED" footer, shared by every channel's settings
 * card so the same outcome reads the same way regardless of transport.
 */
interface Props {
  lastNotifiedAt: string | null;
  lastDeliveryStatus: string | null;
  lastDeliveryError: string | null;
}

export default function ChatDeliveryStatusLine({
  lastNotifiedAt,
  lastDeliveryStatus,
  lastDeliveryError,
}: Props) {
  if (!lastNotifiedAt) {
    return null;
  }

  return (
    <div className="mt-4 small text-muted">
      Last delivery attempt: {formatServerDateTime(lastNotifiedAt)} —{' '}
      <span className={lastDeliveryStatus === 'SENT' ? 'text-success' : 'text-danger'}>
        {lastDeliveryStatus}
      </span>
      {lastDeliveryError && <div>{lastDeliveryError}</div>}
    </div>
  );
}
