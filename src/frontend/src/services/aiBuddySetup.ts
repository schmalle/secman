export interface BuddySetupConfig {
  isActive: boolean;
  apiKey: string;
  baseUrl: string;
  modelName: string;
  maxTokens: number;
  aiBuddyEnabled: boolean;
  aiBuddySingleInstance: boolean;
  aiBuddyProviders: string;
  aiBuddyMaxCostPerJobUsd: number;
  aiBuddyDailyBudgetUsd: number;
  aiBuddyMaxInputPricePerMillion: number;
  aiBuddyMaxOutputPricePerMillion: number;
}

export interface BuddyProviderOption { tag: string; name: string; compatible: boolean; reasons: string[] }
export interface BuddySetupResult { ready: boolean; reasons: string[]; providers: BuddyProviderOption[] }

/** Normalize legacy empty-list omission before sorting or rendering nested provider data. */
export function parseBuddySetupResult(value: unknown): BuddySetupResult {
  const invalid = () => new Error('Provider routes could not be loaded because the server returned an invalid response. Refresh provider routes to retry.');
  const object = (item: unknown): item is Record<string, unknown> =>
    typeof item === 'object' && item !== null && !Array.isArray(item);
  const reasons = (item: unknown): string[] => {
    if (item == null) return [];
    if (!Array.isArray(item) || !item.every(reason => typeof reason === 'string')) throw invalid();
    return item;
  };
  if (!object(value) || (value.ready !== undefined && typeof value.ready !== 'boolean')) throw invalid();
  const routes = value.providers ?? [];
  if (!Array.isArray(routes) || routes.length > 100) throw invalid();
  const providers = routes.map((provider: unknown): BuddyProviderOption => {
    if (!object(provider) || typeof provider.tag !== 'string' ||
      !/^[a-z0-9.-]+(?:\/[a-z0-9.-]+)?$/.test(provider.tag) || typeof provider.name !== 'string' ||
      typeof provider.compatible !== 'boolean') throw invalid();
    const issues = reasons(provider.reasons);
    // Missing reasons are expected for compatible routes, but cannot prove ZDR for an incompatible route.
    if (!provider.compatible && issues.length === 0) throw invalid();
    if (provider.compatible && issues.length > 0) throw invalid();
    return { tag: provider.tag, name: provider.name, compatible: provider.compatible, reasons: issues };
  });
  return { ready: value.ready === true, reasons: reasons(value.reasons), providers };
}

export function orderBuddyProviders(providers: BuddyProviderOption[]): BuddyProviderOption[] {
  return [...providers].sort((a, b) => Number(b.compatible) - Number(a.compatible) ||
    Number(a.reasons.includes('ZERO_RETENTION_REQUIRED')) - Number(b.reasons.includes('ZERO_RETENTION_REQUIRED')) ||
    a.name.localeCompare(b.name) || a.tag.localeCompare(b.tag));
}

/** Local completeness only; provider availability is checked explicitly on the server. */
export function buddySetupMissing(config: BuddySetupConfig, storedKeyPresent = false): string[] {
  const missing: string[] = [];
  if (!config.isActive) missing.push('Set this configuration as active');
  if (!config.aiBuddySingleInstance) missing.push('Confirm that the deployment uses one backend instance');
  if (!config.apiKey.trim() && !storedKeyPresent) missing.push('Enter an OpenRouter API key');
  if (!/^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/.test(config.modelName.trim())) missing.push('Select a concrete model ID');
  if (config.baseUrl.replace(/\/+$/, '') !== 'https://openrouter.ai/api/v1') missing.push('Use the standard OpenRouter base URL');
  if (!Number.isInteger(config.maxTokens) || config.maxTokens < 128 || config.maxTokens > 8000) missing.push('Set Max Tokens between 128 and 8,000 (AI buddy caps output at 4,096)');
  const providers = config.aiBuddyProviders.split(',').map(tag => tag.trim()).filter(Boolean);
  if (!providers.length) missing.push('Select and approve at least one provider route');
  else if (providers.some(tag => !/^[a-z0-9.-]+(?:\/[a-z0-9.-]+)?$/.test(tag))) missing.push('Correct the provider route tags');
  if (config.aiBuddyProviders.length > 512) missing.push('Keep approved provider routes within 512 characters');
  const budgets = [config.aiBuddyMaxCostPerJobUsd, config.aiBuddyDailyBudgetUsd,
    config.aiBuddyMaxInputPricePerMillion, config.aiBuddyMaxOutputPricePerMillion];
  if (budgets.some(value => !Number.isFinite(value) || value <= 0 || value > 1000000)) missing.push('Enter positive budget and price limits');
  return missing;
}

export function buddySetupLabel(config: BuddySetupConfig, storedKeyPresent = false): string {
  if (!config.aiBuddyEnabled) return 'Disabled';
  return buddySetupMissing(config, storedKeyPresent).length ? 'Setup incomplete' : 'Setup complete — provider verification pending';
}
