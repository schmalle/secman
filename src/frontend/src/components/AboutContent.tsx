import React from 'react';

const AboutContent: React.FC = () => {
  return (
    <div className="container-fluid p-4">
      <div className="row">
        <div className="col-12">
          <div className="d-flex justify-content-between align-items-center mb-4">
            <h2>About Secman</h2>
            <span className="badge bg-primary fs-6">v1.0.0</span>
          </div>
          <p>Welcome to the comprehensive security requirements management platform!</p>
        </div>
      </div>

      <div className="row mt-4">
        <div className="col-md-4 mb-3">
          <div className="card">
            <div className="card-body">
              <h5 className="card-title">
                <i className="bi bi-shield-check me-2"></i>
                Key Features
              </h5>
              <p className="card-text">Comprehensive security requirements management and tracking capabilities.</p>
              <ul className="list-unstyled">
                <li><i className="bi bi-check-circle text-success me-2"></i>Requirements management and tracking</li>
                <li><i className="bi bi-check-circle text-success me-2"></i>Standards and norms organization</li>
                <li><i className="bi bi-check-circle text-success me-2"></i>Use case association and filtering</li>
                <li><i className="bi bi-check-circle text-success me-2"></i>Risk assessment capabilities</li>
                <li><i className="bi bi-check-circle text-success me-2"></i>Asset management</li>
                <li><i className="bi bi-check-circle text-success me-2"></i>Role-based access control</li>
                <li><i className="bi bi-check-circle text-success me-2"></i>Export functionality</li>
              </ul>
            </div>
          </div>
        </div>
        
        <div className="col-md-4 mb-3">
          <div className="card">
            <div className="card-body">
              <h5 className="card-title">
                <i className="bi bi-stack me-2"></i>
                Technology Stack
              </h5>
              <p className="card-text">Modern full-stack architecture with proven technologies.</p>
              <div className="mb-3">
                <h6 className="fw-bold">Frontend</h6>
                <ul className="list-unstyled small">
                  <li><i className="bi bi-arrow-right me-2"></i>Astro Static Site Generator</li>
                  <li><i className="bi bi-arrow-right me-2"></i>React Components</li>
                  <li><i className="bi bi-arrow-right me-2"></i>Bootstrap 5 UI Framework</li>
                  <li><i className="bi bi-arrow-right me-2"></i>TypeScript</li>
                </ul>
              </div>
              <div>
                <h6 className="fw-bold">Backend</h6>
                <ul className="list-unstyled small">
                  <li><i className="bi bi-arrow-right me-2"></i>Play Framework (Scala)</li>
                  <li><i className="bi bi-arrow-right me-2"></i>JPA/Hibernate ORM</li>
                  <li><i className="bi bi-arrow-right me-2"></i>MariaDB Database</li>
                  <li><i className="bi bi-arrow-right me-2"></i>RESTful API</li>
                </ul>
              </div>
            </div>
          </div>
        </div>
        
        <div className="col-md-4 mb-3">
          <div className="card">
            <div className="card-body">
              <h5 className="card-title">
                <i className="bi bi-diagram-3 me-2"></i>
                System Architecture
              </h5>
              <p className="card-text">Clear separation between frontend and backend components with secure access control.</p>
              <div className="row mt-3">
                <div className="col-12 mb-2">
                  <div className="text-center p-2 border rounded bg-light">
                    <small className="fw-bold">Frontend (Port 4321)</small>
                    <br/>
                    <small className="text-muted">Astro + React UI</small>
                  </div>
                </div>
                <div className="col-12 mb-2">
                  <div className="text-center p-2 border rounded bg-light">
                    <small className="fw-bold">Backend API (Port 9001)</small>
                    <br/>
                    <small className="text-muted">Play Framework</small>
                  </div>
                </div>
                <div className="col-12">
                  <div className="text-center p-2 border rounded bg-light">
                    <small className="fw-bold">Database</small>
                    <br/>
                    <small className="text-muted">MariaDB</small>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>

      {/* Back to Home button */}
      <div className="row mt-4">
        <div className="col-12">
          <a href="/" className="btn btn-secondary">Back to Home</a>
        </div>
      </div>
    </div>
  );
};

export default AboutContent;
