import React, { useEffect, useState } from 'react';
import { getJson } from '../utils/apiJson';
import AiBuddyPanel from './AiBuddyPanel';
interface SystemAsset { id: number; name: string; osVersion: string | null }
export function AiBuddyForHostname({ hostname }: { hostname: string }) {
  const [asset, setAsset] = useState<SystemAsset | null>(null);
  useEffect(() => {
    let stopped = false;
    void getJson<SystemAsset>(`/api/assets/by-name/${encodeURIComponent(hostname)}`).then(value => {
      if (!stopped) setAsset(value);
    }).catch(() => { if (!stopped) setAsset(null); });
    return () => { stopped = true; };
  }, [hostname]);
  return asset ? <AiBuddyPanel key={asset.id} assetId={asset.id} /> : <small className="text-muted">AI buddy requires an accessible system recorded in SecMan.</small>;
}
