import React from 'react';
import type { NotificationEventType } from '../services/chatNotificationService';

/**
 * The "what to report" checkbox list, shared by every channel's settings card.
 *
 * Rendered from the catalogue the API returns rather than a hardcoded list, so a new
 * backend NotificationEventType appears on every channel with no frontend change. Shared
 * so the two channels cannot drift in wording or behaviour.
 */
interface Props {
  /** Distinguishes the checkbox ids when two channels render the list on one page. */
  idPrefix: string;
  availableEventTypes: NotificationEventType[];
  selected: string[];
  disabled?: boolean;
  onToggle: (name: string) => void;
}

export default function NotificationEventSelector({
  idPrefix,
  availableEventTypes,
  selected,
  disabled = false,
  onToggle,
}: Props) {
  if (availableEventTypes.length === 0) {
    return <p className="text-muted">No notification events are available.</p>;
  }

  return (
    <>
      {availableEventTypes.map((eventType) => {
        const id = `${idPrefix}-event-${eventType.name}`;
        return (
          <div className="form-check mb-3" key={eventType.name}>
            <input
              className="form-check-input"
              type="checkbox"
              id={id}
              checked={selected.includes(eventType.name)}
              onChange={() => onToggle(eventType.name)}
              disabled={disabled}
            />
            <label className="form-check-label" htmlFor={id}>
              <strong>{eventType.displayName}</strong>
              <div className="text-muted small">{eventType.description}</div>
            </label>
          </div>
        );
      })}
    </>
  );
}
