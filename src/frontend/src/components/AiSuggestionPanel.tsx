import React, { useState } from 'react';
import type { AppliedSuggestion, ConfidenceBand } from '../services/aiSuggestions';

/**
 * Feature 088 — AI-Assisted Risk Assessment Answers.
 *
 * Small panel rendered above a single requirement card in AssessmentPerformance,
 * showing the AI's suggested answer, confidence band, rationale, and citations.
 */
interface Props {
  suggestion: AppliedSuggestion;
}

function bandStyles(b: ConfidenceBand): { className: string; label: string } {
  switch (b) {
    case 'HIGH':
      return { className: 'bg-success', label: 'HIGH confidence' };
    case 'MEDIUM':
      return { className: 'bg-warning text-dark', label: 'MEDIUM confidence' };
    case 'LOW':
      return { className: 'bg-danger', label: 'LOW confidence' };
  }
}

const AiSuggestionPanel: React.FC<Props> = ({ suggestion }) => {
  const [expanded, setExpanded] = useState(false);
  const band = bandStyles(suggestion.confidenceBand);
  const pct = Math.round(suggestion.rawConfidence * 100);

  return (
    <div className="alert alert-light border mb-2 p-2 small">
      <div className="d-flex justify-content-between align-items-center">
        <div>
          <span className={`badge ${band.className} me-2`} title={`Raw score ${pct}%`}>
            {band.label}
          </span>
          <strong>AI suggests:</strong>{' '}
          <span className="badge bg-light text-dark border me-2">
            {suggestion.suggestedAnswerType.replace('_', '/')}
          </span>
          {suggestion.webSearchUsed && (
            <span className="text-muted ms-2" title="Web search was used to ground this answer">🌐 web search</span>
          )}
        </div>
        <button
          type="button"
          className="btn btn-sm btn-link p-0"
          onClick={() => setExpanded(v => !v)}
        >
          {expanded ? 'hide details' : 'details'}
        </button>
      </div>
      {expanded && (
        <div className="mt-2">
          {suggestion.rationale && (
            <div className="mb-2">
              <strong>Rationale:</strong> {suggestion.rationale}
            </div>
          )}
          {suggestion.citations.length > 0 && (
            <div className="mb-2">
              <strong>Citations:</strong>
              <ol className="mb-0 ps-3">
                {suggestion.citations.map((c, i) => (
                  <li key={i}>
                    <a
                      href={c.url}
                      target="_blank"
                      rel="noopener noreferrer"
                      title={c.snippet ?? undefined}
                    >
                      {c.title || c.url}
                    </a>
                  </li>
                ))}
              </ol>
            </div>
          )}
          <div className="text-muted">
            <small>
              model: <code>{suggestion.model}</code>,
              prompt v{suggestion.promptVersion}
            </small>
          </div>
        </div>
      )}
    </div>
  );
};

export default AiSuggestionPanel;
