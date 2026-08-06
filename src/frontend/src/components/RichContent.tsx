import React from 'react';
import ReactMarkdown from 'react-markdown';
import DOMPurify from 'dompurify';

// Renders requirement long-text fields. Existing rows store Markdown; rows
// edited via HtmlEditor store HTML. Detect format and render with the matching
// renderer. HTML is always sanitized via DOMPurify before insertion because
// requirements are visible to non-author roles (REQ, SECCHAMPION) and could
// otherwise be a stored XSS sink.
const HTML_TAG_RE = /<\/?(p|div|span|h[1-6]|ul|ol|li|strong|em|b|i|u|a|br|table|tr|td|th|blockquote|code|pre)(\s|\/?>)/i;

export const isLikelyHtml = (s?: string | null): boolean => !!s && HTML_TAG_RE.test(s);

interface RichContentProps {
    value?: string | null;
    className?: string;
}

const RichContent: React.FC<RichContentProps> = ({ value, className }) => {
    if (!value) return null;
    if (isLikelyHtml(value)) {
        // SAFETY: input is sanitized with DOMPurify (XSS-safe HTML allowlist).
        return (
            <div
                className={className}
                dangerouslySetInnerHTML={{
                    __html: DOMPurify.sanitize(value, { USE_PROFILES: { html: true } }),
                }}
            />
        );
    }
    return (
        <div className={className}>
            <ReactMarkdown>{value}</ReactMarkdown>
        </div>
    );
};

export default RichContent;
