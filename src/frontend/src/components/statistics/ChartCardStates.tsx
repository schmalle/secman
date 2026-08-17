/**
 * The three placeholder states every card on the vulnerability statistics page renders
 * before it has a chart to show.
 *
 * All six cards (severity distribution, asset type, temporal trends, age trend, top
 * assets, most common CVEs, most vulnerable products) had their own byte-identical copy
 * of this markup — spinner, danger alert, inbox icon — differing only in the sentence
 * underneath. Keeping them here means the page cannot drift into three different
 * spinners, and a change to the empty-state wording is one edit.
 */

import React from 'react';

interface CardHeading {
    /** Bootstrap-icons class, e.g. `bi-pie-chart`. */
    icon: string;
    title: string;
}

/** Card header shared by the empty states that carry one. */
function CardHeader({ icon, title }: CardHeading) {
    return (
        <div className="card-header">
            <h5 className="mb-0">
                <i className={`bi ${icon} me-2`}></i>
                {title}
            </h5>
        </div>
    );
}

/**
 * Spinner shown while the card's data is in flight.
 *
 * @param label Sentence under the spinner, e.g. "Loading severity distribution..."
 */
export function ChartCardLoading({ label }: { label: string }) {
    return (
        <div className="card">
            <div className="card-body text-center py-5">
                <div className="spinner-border text-primary" role="status">
                    <span className="visually-hidden">Loading...</span>
                </div>
                <p className="mt-3 text-muted">{label}</p>
            </div>
        </div>
    );
}

/** Danger alert shown when the card's fetch failed. */
export function ChartCardError({ message }: { message: string }) {
    return (
        <div className="card">
            <div className="card-body">
                <div className="alert alert-danger" role="alert">
                    <i className="bi bi-exclamation-triangle me-2"></i>
                    {message}
                </div>
            </div>
        </div>
    );
}

interface ChartCardEmptyProps {
    /** Header to keep above the placeholder; omit for cards that render none. */
    heading?: CardHeading;
    /** Primary line, e.g. "No vulnerability data available." */
    message: string;
    /** Optional smaller line explaining how to populate the card. */
    hint?: string;
}

/** Placeholder shown when the fetch succeeded but returned nothing to chart. */
export function ChartCardEmpty({ heading, message, hint }: ChartCardEmptyProps) {
    return (
        <div className="card">
            {heading && <CardHeader {...heading} />}
            <div className="card-body text-center py-5">
                <i className="bi bi-inbox display-4 text-muted"></i>
                <p className="mt-3 text-muted">{message}</p>
                {hint && <small className="text-muted">{hint}</small>}
            </div>
        </div>
    );
}
