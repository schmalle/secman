import React, { useState, useEffect } from 'react';
import { authenticatedGet } from '../utils/auth';

interface UseCase {
  id: number;
  name: string;
  description?: string;
  createdAt?: string;
  updatedAt?: string;
}

interface Standard {
  id: number;
  name: string;
  description?: string;
  useCases?: UseCase[];
  createdAt?: string;
  updatedAt?: string;
}

interface Requirement {
  id: number;
  shortreq: string;
  details?: string;
  language?: string;
  example?: string;
  motivation?: string;
  usecase?: string;
  norm?: string;
  chapter?: string;
  usecases: any[];
  norms: any[];
  createdAt?: string;
  updatedAt?: string;
}

interface Chapter {
  name: string;
  requirements: Requirement[];
}

interface ViewStandardProps {
  standardId: string;
}

const ViewStandard: React.FC<ViewStandardProps> = ({ standardId }) => {
  const [standard, setStandard] = useState<Standard | null>(null);
  const [requirements, setRequirements] = useState<Requirement[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    fetchStandardDetails();
    fetchStandardRequirements();
  }, [standardId]);

  const fetchStandardDetails = async () => {
    try {
      const response = await authenticatedGet(`/api/standards/${standardId}`);
      if (response.ok) {
        const data = await response.json();
        setStandard(data);
      } else {
        setError(`Failed to fetch standard: ${response.status}`);
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : 'An error occurred');
    }
  };

  const fetchStandardRequirements = async () => {
    try {
      const response = await authenticatedGet(`/api/standards/${standardId}/requirements`);
      if (response.ok) {
        const data = await response.json();
        setRequirements(data);
      } else {
        console.warn(`Failed to fetch requirements: ${response.status}`);
        setRequirements([]);
      }
    } catch (err) {
      console.warn('Failed to fetch requirements:', err);
      setRequirements([]);
    } finally {
      setLoading(false);
    }
  };

  // Group requirements by chapter
  const getChapterStructure = (): Chapter[] => {
    if (!requirements || requirements.length === 0) {
      return [];
    }

    // Group requirements by chapter
    const groupedByChapter = requirements.reduce((acc: { [key: string]: Requirement[] }, req) => {
      const chapterName = req.chapter || 'Uncategorized';
      if (!acc[chapterName]) {
        acc[chapterName] = [];
      }
      acc[chapterName].push(req);
      return acc;
    }, {});

    // Convert to Chapter array and sort
    const chapters: Chapter[] = Object.entries(groupedByChapter).map(([chapterName, reqs]) => ({
      name: chapterName,
      requirements: reqs.sort((a, b) => a.shortreq.localeCompare(b.shortreq))
    }));

    // Sort chapters by name
    return chapters.sort((a, b) => a.name.localeCompare(b.name));
  };

  const renderStandardStructure = () => {
    const chapters = getChapterStructure();
    
    return (
      <div className="accordion" id="standardStructureAccordion">
        {chapters.map((chapter, index) => (
          <div className="accordion-item" key={chapter.name}>
            <h2 className="accordion-header" id={`heading${index}`}>
              <button
                className={`accordion-button ${index !== 0 ? 'collapsed' : ''}`}
                type="button"
                data-bs-toggle="collapse"
                data-bs-target={`#collapse${index}`}
                aria-expanded={index === 0 ? "true" : "false"}
                aria-controls={`collapse${index}`}
              >
                <strong>{chapter.name}</strong>
                <span className="badge bg-secondary ms-2">{chapter.requirements.length} requirements</span>
              </button>
            </h2>
            <div
              id={`collapse${index}`}
              className={`accordion-collapse collapse ${index === 0 ? 'show' : ''}`}
              aria-labelledby={`heading${index}`}
              data-bs-parent="#standardStructureAccordion"
            >
              <div className="accordion-body">
                {chapter.requirements && chapter.requirements.length > 0 ? (
                  <div className="list-group list-group-flush">
                    {chapter.requirements.map((req) => (
                      <div key={req.id} className="list-group-item px-0">
                        <div className="d-flex justify-content-between align-items-start">
                          <div className="flex-grow-1">
                            <h6 className="mb-1">
                              <span className="text-primary">{req.shortreq}</span>
                            </h6>
                            {req.details && (
                              <p className="mb-1 text-muted small">{req.details}</p>
                            )}
                            {req.example && (
                              <div className="mb-1">
                                <small className="text-success"><strong>Example:</strong> {req.example}</small>
                              </div>
                            )}
                            {req.motivation && (
                              <div className="mb-1">
                                <small className="text-info"><strong>Motivation:</strong> {req.motivation}</small>
                              </div>
                            )}
                            <div className="mt-2">
                              {req.language && (
                                <span className="badge bg-light text-dark me-1">Lang: {req.language}</span>
                              )}
                              {req.usecases && req.usecases.length > 0 && (
                                <span className="badge bg-info me-1">{req.usecases.length} Use Cases</span>
                              )}
                              {req.norms && req.norms.length > 0 && (
                                <span className="badge bg-warning text-dark me-1">{req.norms.length} Norms</span>
                              )}
                            </div>
                          </div>
                          <button className="btn btn-sm btn-outline-secondary ms-2" title="View requirement details">
                            <i className="bi bi-eye"></i>
                          </button>
                        </div>
                      </div>
                    ))}
                  </div>
                ) : (
                  <p className="text-muted mb-0">No requirements defined for this chapter</p>
                )}
              </div>
            </div>
          </div>
        ))}
        
        {chapters.length === 0 && (
          <div className="alert alert-info mb-0">
            <i className="bi bi-info-circle"></i> No requirements have been found for this standard.
            {requirements.length === 0 && <span> This may be because no requirements are associated with the standard's use cases.</span>}
          </div>
        )}
      </div>
    );
  };

  if (loading) {
    return (
      <div className="d-flex justify-content-center p-5">
        <div className="spinner-border" role="status">
          <span className="visually-hidden">Loading...</span>
        </div>
      </div>
    );
  }

  if (error || !standard) {
    return (
      <div className="container-fluid p-4">
        <div className="alert alert-danger" role="alert">
          {error || 'Standard not found'}
        </div>
        <a href="/standards" className="btn btn-secondary">Back to Standards</a>
      </div>
    );
  }

  return (
    <div className="container-fluid p-4">
      <div className="row">
        <div className="col-12">
          <div className="d-flex justify-content-between align-items-center mb-4">
            <h2>Standard Details</h2>
            <div>
              <a href={`/standards/edit/${standard.id}`} className="btn btn-primary me-2">
                <i className="bi bi-pencil"></i> Edit
              </a>
              <a href="/standards" className="btn btn-secondary">
                <i className="bi bi-arrow-left"></i> Back to Standards
              </a>
            </div>
          </div>
        </div>
      </div>

      <div className="row">
        <div className="col-12">
          <div className="card mb-4">
            <div className="card-header bg-primary text-white">
              <h4 className="mb-0">{standard.name}</h4>
            </div>
            <div className="card-body">
              <div className="row">
                <div className="col-md-6">
                  <h5>Basic Information</h5>
                  <dl className="row">
                    <dt className="col-sm-4">Name:</dt>
                    <dd className="col-sm-8">{standard.name}</dd>
                    
                    <dt className="col-sm-4">Description:</dt>
                    <dd className="col-sm-8">
                      {standard.description || <span className="text-muted">No description provided</span>}
                    </dd>
                    
                    <dt className="col-sm-4">Created:</dt>
                    <dd className="col-sm-8">
                      {standard.createdAt ? new Date(standard.createdAt).toLocaleString() : '-'}
                    </dd>
                    
                    <dt className="col-sm-4">Last Updated:</dt>
                    <dd className="col-sm-8">
                      {standard.updatedAt ? new Date(standard.updatedAt).toLocaleString() : '-'}
                    </dd>
                  </dl>
                </div>
                
                <div className="col-md-6">
                  <h5>Associated Use Cases ({standard.useCases?.length || 0})</h5>
                  {standard.useCases && standard.useCases.length > 0 ? (
                    <div className="list-group">
                      {standard.useCases.map((useCase) => (
                        <div key={useCase.id} className="list-group-item">
                          <div className="d-flex justify-content-between align-items-start">
                            <div>
                              <h6 className="mb-1">{useCase.name}</h6>
                              {useCase.description && (
                                <p className="mb-1 text-muted small">{useCase.description}</p>
                              )}
                            </div>
                            <span className="badge bg-info">Use Case</span>
                          </div>
                        </div>
                      ))}
                    </div>
                  ) : (
                    <p className="text-muted">No use cases associated with this standard</p>
                  )}
                </div>
              </div>
            </div>
          </div>

          {/* Standard Structure Section */}
          <div className="card">
            <div className="card-header">
              <h5 className="mb-0">Standard Structure</h5>
            </div>
            <div className="card-body">
              {renderStandardStructure()}
            </div>
          </div>
        </div>
      </div>
    </div>
  );
};

export default ViewStandard;