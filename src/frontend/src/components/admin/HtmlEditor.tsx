import { useEffect, useRef, useState } from 'react';

interface Props {
  value: string;
  onChange: (html: string) => void;
  minHeight?: number;
}

interface ToolbarButton {
  cmd: string;
  arg?: string;
  icon: string;
  title: string;
}

const TOOLBAR: ToolbarButton[] = [
  { cmd: 'bold', icon: 'bi-type-bold', title: 'Bold' },
  { cmd: 'italic', icon: 'bi-type-italic', title: 'Italic' },
  { cmd: 'underline', icon: 'bi-type-underline', title: 'Underline' },
  { cmd: 'formatBlock', arg: 'h2', icon: 'bi-type-h2', title: 'Heading' },
  { cmd: 'formatBlock', arg: 'p', icon: 'bi-paragraph', title: 'Paragraph' },
  { cmd: 'insertUnorderedList', icon: 'bi-list-ul', title: 'Bullet list' },
  { cmd: 'insertOrderedList', icon: 'bi-list-ol', title: 'Numbered list' },
];

export default function HtmlEditor({ value, onChange, minHeight = 280 }: Props) {
  const ref = useRef<HTMLDivElement | null>(null);
  const [showSource, setShowSource] = useState(false);
  const lastEmittedRef = useRef(value);

  useEffect(() => {
    if (!ref.current) return;
    if (value !== lastEmittedRef.current) {
      ref.current.innerHTML = value;
      lastEmittedRef.current = value;
    }
  }, [value]);

  const exec = (cmd: string, arg?: string) => {
    ref.current?.focus();
    document.execCommand(cmd, false, arg);
    handleInput();
  };

  const insertLink = () => {
    const url = window.prompt('Link URL (https://...)');
    if (!url) return;
    exec('createLink', url);
  };

  const handleInput = () => {
    if (!ref.current) return;
    const html = ref.current.innerHTML;
    lastEmittedRef.current = html;
    onChange(html);
  };

  return (
    <div className="border rounded">
      <div className="d-flex flex-wrap align-items-center gap-1 px-2 py-1 border-bottom bg-light">
        {TOOLBAR.map((b) => (
          <button
            key={`${b.cmd}-${b.arg ?? ''}`}
            type="button"
            className="btn btn-sm btn-outline-secondary"
            title={b.title}
            onMouseDown={(e) => e.preventDefault()}
            onClick={() => exec(b.cmd, b.arg)}
          >
            <i className={`bi ${b.icon}`}></i>
          </button>
        ))}
        <button
          type="button"
          className="btn btn-sm btn-outline-secondary"
          title="Insert link"
          onMouseDown={(e) => e.preventDefault()}
          onClick={insertLink}
        >
          <i className="bi bi-link-45deg"></i>
        </button>
        <button
          type="button"
          className="btn btn-sm btn-outline-secondary"
          title="Remove formatting"
          onMouseDown={(e) => e.preventDefault()}
          onClick={() => exec('removeFormat')}
        >
          <i className="bi bi-eraser"></i>
        </button>
        <div className="ms-auto">
          <button
            type="button"
            className={`btn btn-sm ${showSource ? 'btn-secondary' : 'btn-outline-secondary'}`}
            onClick={() => setShowSource((v) => !v)}
          >
            <i className="bi bi-code-slash me-1"></i>
            {showSource ? 'Editor' : 'HTML'}
          </button>
        </div>
      </div>

      {showSource ? (
        <textarea
          className="form-control border-0"
          style={{ minHeight, fontFamily: 'monospace', fontSize: 13, resize: 'vertical' }}
          value={value}
          onChange={(e) => {
            lastEmittedRef.current = e.target.value;
            onChange(e.target.value);
          }}
        />
      ) : (
        <div
          ref={ref}
          contentEditable
          suppressContentEditableWarning
          className="p-3"
          style={{ minHeight, outline: 'none' }}
          onInput={handleInput}
          onBlur={handleInput}
        />
      )}
    </div>
  );
}
