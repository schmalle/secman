import { createPopper, type Instance } from '@popperjs/core';
import { useEffect, useId, useRef } from 'react';
import type { CrowdStrikeRemediationDto } from '../services/crowdstrikeService';
import { safeEvidenceUrl } from '../services/integrationPresentation';

export function CrowdStrikeRemediation({ guidance }: { guidance: CrowdStrikeRemediationDto[] }) {
    const panelId = useId();
    const trigger = useRef<HTMLButtonElement>(null);
    const panel = useRef<HTMLDivElement>(null);
    const position = useRef<Instance | null>(null);
    const closeTimer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
    const pinned = useRef(false);

    useEffect(() => () => {
        clearTimeout(closeTimer.current);
        position.current?.destroy();
    }, []);

    function show() {
        clearTimeout(closeTimer.current);
        const element = panel.current;
        const button = trigger.current;
        if (!element || !button || element.matches(':popover-open')) return;
        element.showPopover();
        position.current = createPopper(button, element, {
            strategy: 'fixed',
            placement: 'bottom-start',
            modifiers: [{ name: 'offset', options: { offset: [0, 4] } }]
        });
        button.setAttribute('aria-expanded', 'true');
    }

    function hide() {
        clearTimeout(closeTimer.current);
        pinned.current = false;
        position.current?.destroy();
        position.current = null;
        trigger.current?.setAttribute('aria-expanded', 'false');
        if (panel.current?.matches(':popover-open')) panel.current.hidePopover();
    }

    function endPreview() {
        clearTimeout(closeTimer.current);
        closeTimer.current = setTimeout(() => {
            if (!pinned.current && !panel.current?.contains(document.activeElement)) hide();
        }, 150);
    }

    function closeAndReturnFocus() {
        trigger.current?.focus();
        hide();
    }

    return (
        <>
            <button
                ref={trigger}
                type="button"
                className="btn btn-sm btn-outline-secondary py-0 px-1"
                aria-label="CrowdStrike remediation"
                aria-expanded="false"
                aria-controls={panelId}
                onPointerEnter={event => { if (event.pointerType === 'mouse') show(); }}
                onPointerLeave={endPreview}
                onFocus={show}
                onBlur={endPreview}
                onClick={() => {
                    if (pinned.current) { hide(); return; }
                    show();
                    pinned.current = true;
                }}
                onKeyDown={event => { if (event.key === 'Escape') hide(); }}
            >
                <i className="bi bi-wrench" aria-hidden="true" />
            </button>
            <div
                ref={panel}
                id={panelId}
                popover="auto"
                className="border rounded bg-body text-body shadow-sm p-3"
                role="region"
                aria-label="CrowdStrike remediation"
                style={{ inset: 'auto', margin: 0, width: 'min(24rem, calc(100vw - 1rem))', maxHeight: 'min(50vh, 24rem)', overflowY: 'auto' }}
                onPointerEnter={() => clearTimeout(closeTimer.current)}
                onPointerLeave={endPreview}
                onBlur={event => {
                    if (!event.currentTarget.contains(event.relatedTarget)) endPreview();
                }}
                onToggle={event => { if (event.newState === 'closed') hide(); }}
                onKeyDown={event => { if (event.key === 'Escape') closeAndReturnFocus(); }}
            >
                <div className="d-flex justify-content-between align-items-start gap-2 mb-2">
                    <strong>CrowdStrike remediation</strong>
                    <button type="button" className="btn-close" aria-label="Close remediation" onClick={closeAndReturnFocus} />
                </div>
                <ul className="list-unstyled mb-0">
                    {guidance.map(({ action, title, vendorUrl }, index) => {
                        const url = safeEvidenceUrl(vendorUrl);
                        const vendor = url?.startsWith('https://') ? new URL(url) : null;
                        const vendorHostname = vendor?.hostname.replace(/\.$/, '');
                        const externalVendor = vendor && !vendor.port && vendorHostname &&
                            /\.[a-z][a-z0-9-]*$/i.test(vendorHostname) &&
                            !/\.(local|localhost|internal)$/i.test(vendorHostname);
                        return (
                            <li key={index} className="mt-2 text-break">
                                {title && <div className="fw-semibold">{title}</div>}
                                <div style={{ whiteSpace: 'pre-line' }}>{action}</div>
                                {externalVendor && (
                                    <a href={vendor.href} target="_blank" rel="noopener noreferrer">
                                        Vendor guidance ({vendorHostname})
                                    </a>
                                )}
                            </li>
                        );
                    })}
                </ul>
            </div>
        </>
    );
}
