import React, { useEffect, useState } from 'react';
import HtmlEditor from './admin/HtmlEditor';
import { getInstalledProducts, getInstalledProductsByServer, type InstalledProductResponse } from '../services/installedProductService';
import {
  createProductBroadcast,
  getProductRecipientCount,
  type EmailBroadcastJob
} from '../services/emailBroadcastService';
import { getUser } from '../utils/auth';
import { canNotifyProductUsers } from './productNotifyAccess';

const InstalledProducts: React.FC = () => {
  const [products, setProducts] = useState<InstalledProductResponse[]>([]);
  const [search, setSearch] = useState('');
  const [serverSearch, setServerSearch] = useState('');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [totalSystems, setTotalSystems] = useState(0);
  const [notifyProduct, setNotifyProduct] = useState<string | null>(null);
  const [notifySubject, setNotifySubject] = useState('');
  const [notifyHtml, setNotifyHtml] = useState('');
  const [notifyRecipientCount, setNotifyRecipientCount] = useState<number | null>(null);
  const [loadingNotifyRecipients, setLoadingNotifyRecipients] = useState(false);
  const [sendingNotification, setSendingNotification] = useState(false);
  const [notifyJob, setNotifyJob] = useState<EmailBroadcastJob | null>(null);
  const [notifyError, setNotifyError] = useState<string | null>(null);
  const [canNotifyUsers] = useState(() => canNotifyProductUsers(getUser()?.roles));

  const notifyProductName = products.length > 0 ? products[0].name : search.trim();

  useEffect(() => {
    const timeout = window.setTimeout(() => {
      setLoading(true);
      const request = serverSearch.trim()
        ? getInstalledProductsByServer(serverSearch)
        : getInstalledProducts(search);

      request
        .then((response) => {
          setProducts(response.products ?? []);
          setTotalSystems(response.totalSystems);
          setError(null);
        })
        .catch((err) => setError(err instanceof Error ? err.message : 'Failed to load installed products'))
        .finally(() => setLoading(false));
    }, 250);

    return () => window.clearTimeout(timeout);
  }, [search, serverSearch]);

  const openNotifyModal = async (productName: string) => {
    setNotifyProduct(productName);
    setNotifySubject(`Action required for ${productName}`);
    setNotifyHtml(`<p>Hello,</p><p>please review the systems assigned to you that are running <strong>${productName}</strong>.</p>`);
    setNotifyRecipientCount(null);
    setNotifyJob(null);
    setNotifyError(null);
    setLoadingNotifyRecipients(true);

    try {
      const count = await getProductRecipientCount(productName);
      setNotifyRecipientCount(count);
    } catch (err) {
      console.error('Failed to load product notification recipients:', err);
      setNotifyError(err instanceof Error ? err.message : 'Failed to load recipients for this product.');
    } finally {
      setLoadingNotifyRecipients(false);
    }
  };

  const handleSendProductNotification = async () => {
    if (!notifyProduct) return;

    const trimmedSubject = notifySubject.trim();
    const trimmedHtml = notifyHtml.trim();
    if (!trimmedSubject || !trimmedHtml) {
      setNotifyError('Subject and message are required.');
      return;
    }

    setSendingNotification(true);
    setNotifyError(null);

    try {
      const job = await createProductBroadcast({
        productName: notifyProduct,
        subject: trimmedSubject,
        htmlContent: trimmedHtml
      });
      setNotifyJob(job);
      setNotifyRecipientCount(job.totalRecipients);
    } catch (err) {
      console.error('Failed to send product notification:', err);
      setNotifyError(err instanceof Error ? err.message : 'Failed to send product notification.');
    } finally {
      setSendingNotification(false);
    }
  };

  return (
    <div className="container-fluid py-4">
      <div className="d-flex justify-content-between align-items-center mb-4">
        <div>
          <h1 className="h3 mb-1">Installed products</h1>
          <p className="text-muted mb-0">Products imported from CrowdStrike Discover for systems known in Secman. Search a server to return the products installed on matching systems.</p>
        </div>
        <div className="text-end">
          <div className="fw-semibold">{products.length}</div>
          <div className="text-muted small">rows shown across {totalSystems} systems</div>
        </div>
      </div>

      <div className="row mb-4">
        <div className="col-12">
          <div className="card">
            <div className="card-body">
              <h6 className="card-title">Search</h6>
              <div className="row g-3">
                <div className="col-12 col-lg-6">
                  <label className="form-label" htmlFor="installed-product-server-search">Search by server</label>
                  <input
                    id="installed-product-server-search"
                    className="form-control"
                    value={serverSearch}
                    onChange={(event) => setServerSearch(event.target.value)}
                    placeholder="e.g. server01 or 10.0.0.15"
                  />
                  <div className="form-text">Returns the installed products for matching server names or IP addresses.</div>
                </div>
                <div className="col-12 col-lg-6">
                  <label className="form-label" htmlFor="installed-product-search">Search products, vendors, versions, or systems</label>
                  <div className="d-flex gap-2">
                    <input
                      id="installed-product-search"
                      className="form-control"
                      value={search}
                      onChange={(event) => setSearch(event.target.value)}
                      placeholder="e.g. Chrome, Microsoft, server01"
                      disabled={Boolean(serverSearch.trim())}
                    />
                    {canNotifyUsers && notifyProductName && (
                      <button
                        type="button"
                        className="btn btn-outline-primary flex-shrink-0"
                        onClick={() => openNotifyModal(notifyProductName)}
                        disabled={loading || products.length === 0}
                        title={`Notify users for ${notifyProductName}`}
                      >
                        <i className="bi bi-envelope me-1"></i>
                        Notify users
                      </button>
                    )}
                  </div>
                  {serverSearch.trim() && (
                    <div className="form-text">Product search is paused while server search is active.</div>
                  )}
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>

      {error && <div className="alert alert-danger">{error}</div>}

      <div className="row">
        <div className="col-12">
          <div className="card">
            <div className="card-body">
              <h5 className="card-title">Products ({products.length})</h5>
              <div className="table-responsive">
                <table className="table table-striped table-hover">
                  <thead>
                    <tr>
                      <th>Product</th>
                      <th>Version</th>
                      <th>System</th>
                      <th>AWS Account ID</th>
                    </tr>
                  </thead>
                  <tbody>
                    {loading ? (
                      <tr><td colSpan={4} className="text-center py-4">Loading installed products...</td></tr>
                    ) : products.length === 0 ? (
                      <tr><td colSpan={4} className="text-center py-4 text-muted">No installed products found.</td></tr>
                    ) : products.map((product) => (
                      <tr key={product.id}>
                        <td className="fw-semibold">{product.name}</td>
                        <td><span className="text-danger">{product.version || '—'}</span></td>
                        <td><a href={`/assets/${product.assetId}`}>{product.hostname}</a></td>
                        <td>{product.cloudAccountId || '–'}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
          </div>
        </div>
      </div>

      {notifyProduct && (
        <>
          <div className="modal d-block" tabIndex={-1} role="dialog" aria-modal="true">
            <div className="modal-dialog modal-lg modal-dialog-centered">
              <div className="modal-content">
                <div className="modal-header">
                  <h2 className="modal-title h5">
                    <i className="bi bi-envelope me-2"></i>
                    Notify product users
                  </h2>
                  <button
                    type="button"
                    className="btn-close"
                    onClick={() => setNotifyProduct(null)}
                    aria-label="Close"
                    disabled={sendingNotification}
                  ></button>
                </div>
                <div className="modal-body">
                  <div className="d-flex flex-wrap align-items-center gap-2 mb-3">
                    <span className="badge bg-secondary">{notifyProduct}</span>
                    {loadingNotifyRecipients ? (
                      <span className="text-muted small">
                        <span className="spinner-border spinner-border-sm me-1" role="status"></span>
                        Loading recipients...
                      </span>
                    ) : (
                      <span className="text-muted small">
                        {notifyRecipientCount ?? 0} recipient{notifyRecipientCount === 1 ? '' : 's'}
                      </span>
                    )}
                  </div>

                  {notifyError && (
                    <div className="alert alert-danger" role="alert">
                      <i className="bi bi-exclamation-triangle me-2"></i>
                      {notifyError}
                    </div>
                  )}

                  {notifyJob && (
                    <div className="alert alert-success" role="alert">
                      <i className="bi bi-check-circle me-2"></i>
                      Broadcast job #{notifyJob.id} was queued for {notifyJob.totalRecipients} recipient{notifyJob.totalRecipients === 1 ? '' : 's'}.
                    </div>
                  )}

                  <div className="mb-3">
                    <label htmlFor="installedProductNotifySubject" className="form-label">
                      Subject
                    </label>
                    <input
                      id="installedProductNotifySubject"
                      type="text"
                      className="form-control"
                      value={notifySubject}
                      onChange={(event) => setNotifySubject(event.target.value)}
                      disabled={sendingNotification || Boolean(notifyJob)}
                      maxLength={255}
                    />
                  </div>

                  <div className="mb-0">
                    <label className="form-label">Message</label>
                    <HtmlEditor value={notifyHtml} onChange={setNotifyHtml} minHeight={220} />
                  </div>
                </div>
                <div className="modal-footer">
                  <button
                    type="button"
                    className="btn btn-outline-secondary"
                    onClick={() => setNotifyProduct(null)}
                    disabled={sendingNotification}
                  >
                    Close
                  </button>
                  <button
                    type="button"
                    className="btn btn-primary"
                    onClick={handleSendProductNotification}
                    disabled={
                      sendingNotification ||
                      loadingNotifyRecipients ||
                      Boolean(notifyJob) ||
                      notifyRecipientCount === 0
                    }
                  >
                    {sendingNotification ? (
                      <>
                        <span className="spinner-border spinner-border-sm me-1" role="status"></span>
                        Sending...
                      </>
                    ) : (
                      <>
                        <i className="bi bi-send me-1"></i>
                        Send notification
                      </>
                    )}
                  </button>
                </div>
              </div>
            </div>
          </div>
          <div className="modal-backdrop show"></div>
        </>
      )}
    </div>
  );
};

export default InstalledProducts;
