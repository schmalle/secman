import { useEffect, useId, useRef, useState, type FormEvent } from 'react';
import { authenticatedDelete, authenticatedGet, authenticatedPost } from '../utils/auth';
import { useClientRoles } from '../utils/useClientAuth';
import { formatServerDateTime } from '../utils/dateUtils';

interface Comment {
  id: number;
  body: string;
  authorName: string;
  createdAt: string;
  requirementRevision?: number;
  canDelete: boolean;
}

interface CommentPage {
  data?: Comment[];
  totalPages: number;
  canComment: boolean;
  targetRevision?: number;
}

interface Props {
  scope: 'REQUIREMENT' | 'RELEASE' | 'SNAPSHOT';
  targetId: number;
  context: string;
  revision?: number;
}

/** The key resets drafts and requests when the selected target changes. */
export default function RequirementComments(props: Props) {
  const roles = useClientRoles();
  const allowed = props.scope === 'REQUIREMENT'
    ? ['ADMIN', 'REQ', 'SECCHAMPION']
    : ['ADMIN', 'REQ', 'REQADMIN', 'SECCHAMPION', 'RELEASE_MANAGER'];
  if (!roles.some(role => allowed.includes(role))) return null;
  return <Discussion key={`${props.scope}-${props.targetId}`} {...props} />;
}

function Discussion({ scope, targetId, context, revision }: Props) {
  const inputId = useId();
  const [result, setResult] = useState<CommentPage | null>(null);
  const [page, setPage] = useState(0);
  const [body, setBody] = useState('');
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [refresh, setRefresh] = useState(0);
  const pending = useRef(false);
  const url = `/api/requirement-comments/${scope}/${targetId}`;

  useEffect(() => {
    let active = true;
    setLoading(true);
    authenticatedGet(`${url}?page=${page}&size=20`).then(async response => {
      if (!response.ok) throw new Error('Unable to load comments. Retry or reload this page.');
      const data: CommentPage = await response.json();
      if (active) setResult({ ...data, data: Array.isArray(data.data) ? data.data : [] });
    }).catch(error => {
      if (active) setError(error.message);
    }).finally(() => {
      if (active) setLoading(false);
    });
    return () => { active = false; };
  }, [url, page, refresh]);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    if (pending.current) return;
    if (!body.trim() || body.length > 2000) {
      setError('Enter a comment of 1 to 2000 characters.');
      return;
    }
    pending.current = true;
    setSaving(true);
    setError('');
    setNotice('');
    try {
      const response = await authenticatedPost(url, {
        body, expectedRevision: revision ?? result?.targetRevision ?? null,
      });
      if (!response.ok) {
        const data = await response.json().catch(() => ({}));
        throw new Error(data.message || 'Unable to save comment. Your text has been preserved.');
      }
      setBody('');
      setNotice('Comment added.');
      setPage(0);
      setRefresh(value => value + 1);
    } catch (error) {
      setError(error instanceof Error ? error.message : 'Unable to save comment.');
    } finally {
      pending.current = false;
      setSaving(false);
    }
  };

  const deleteComment = async (commentId: number) => {
    if (pending.current || !window.confirm('Delete this comment permanently?')) return;
    pending.current = true;
    setSaving(true);
    setError('');
    setNotice('');
    try {
      const response = await authenticatedDelete(`${url}/${commentId}`);
      if (!response.ok) throw new Error('Unable to delete comment. Retry or reload this page.');
      setNotice('Comment deleted.');
      setPage(0);
      setRefresh(value => value + 1);
    } catch (error) {
      setError(error instanceof Error ? error.message : 'Unable to delete comment.');
    } finally {
      pending.current = false;
      setSaving(false);
    }
  };

  return (
    <section className="card my-3" aria-label={`Comments: ${context}`}>
      <div className="card-body">
        <h4>Comments</h4>
        <p className="text-muted small">{context}. Comments are visible only to their author, admins, and security champions.</p>
        {error && <div className="alert alert-danger" role="alert">{error}
          <button type="button" className="btn btn-link" disabled={loading || saving}
            onClick={() => { setError(''); setRefresh(value => value + 1); }}>Retry loading comments</button>
        </div>}
        {notice && <p role="status">{notice}</p>}
        {loading && <p role="status">Loading comments…</p>}
        {!loading && result && <>
          {(result.data ?? []).length === 0 && <p>No comments yet.</p>}
          <ul className="list-unstyled">
            {(result.data ?? []).map(comment => <li key={comment.id} className="border-bottom py-2">
              <div className="small text-muted">{comment.authorName} · {formatServerDateTime(comment.createdAt)}
                {comment.requirementRevision != null && ` · Revision ${comment.requirementRevision}`}</div>
              {comment.canDelete && <button type="button" className="btn btn-sm btn-outline-danger my-1"
                disabled={saving} onClick={() => deleteComment(comment.id)}>Delete comment</button>}
              <p className="mb-0" style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{comment.body}</p>
            </li>)}
          </ul>
          {result.totalPages > 1 && <nav aria-label="Comment pagination" className="d-flex gap-2 align-items-center">
            <button type="button" className="btn btn-sm btn-outline-secondary" disabled={page === 0 || saving}
              onClick={() => setPage(value => value - 1)}>Newer comments</button>
            <span>Page {page + 1} of {result.totalPages}</span>
            <button type="button" className="btn btn-sm btn-outline-secondary" disabled={page + 1 >= result.totalPages || saving}
              onClick={() => setPage(value => value + 1)}>Older comments</button>
          </nav>}
          {result.canComment ? <form onSubmit={submit} className="mt-3">
            <label className="form-label" htmlFor={inputId}>Add a comment</label>
            <textarea id={inputId} className="form-control" rows={3} maxLength={2000} required
              aria-describedby={`${inputId}-help`} value={body} disabled={saving}
              onChange={event => setBody(event.target.value)} />
            <div id={`${inputId}-help`} className="form-text">Plain text · {body.length}/2000 characters</div>
            <button className="btn btn-primary mt-2" type="submit" disabled={saving || !body.trim()}>
              {saving ? 'Adding comment…' : 'Add comment'}
            </button>
          </form> : <p className="text-muted">This discussion is read-only because the release is archived or the requirement is historical.</p>}
        </>}
      </div>
    </section>
  );
}
