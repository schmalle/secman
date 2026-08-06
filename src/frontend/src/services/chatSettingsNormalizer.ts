import type { NotificationEventType } from './chatNotificationService';

/**
 * Shape shared by the Slack and Telegram settings responses: the events the user is
 * subscribed to, plus the catalogue of events that can be subscribed to.
 */
export interface ChatSettingsArrays {
  eventTypes: string[];
  availableEventTypes: NotificationEventType[];
}

/**
 * Fill in the array fields a chat-settings response can legitimately arrive without.
 *
 * Micronaut Serde omits empty collections from the serialized body, so a user who has not
 * subscribed to anything yet receives a payload with **no `eventTypes` key at all** rather
 * than `eventTypes: []`. Handing that straight to React state made
 * `NotificationEventSelector` call `.includes()` on `undefined` and take both settings
 * cards down with an uncaught TypeError — the page rendered its heading and nothing else.
 *
 * Normalising here rather than in the components keeps every consumer of the service safe,
 * and matches how the other services in this app absorb the same serializer behaviour
 * (`data.products ?? []`, `data.adDomains ?? []`).
 */
export function normalizeChatSettings<T extends ChatSettingsArrays>(raw: T): T {
  const data = (raw ?? {}) as T;
  return {
    ...data,
    eventTypes: Array.isArray(data.eventTypes) ? data.eventTypes : [],
    availableEventTypes: Array.isArray(data.availableEventTypes) ? data.availableEventTypes : [],
  };
}
