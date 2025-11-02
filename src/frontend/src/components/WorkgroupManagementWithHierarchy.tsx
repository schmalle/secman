import React, { useState } from 'react';
import WorkgroupTree from './WorkgroupTree';
import CreateChildWorkgroupModal from './CreateChildWorkgroupModal';
import MoveWorkgroupModal from './MoveWorkgroupModal';
import DeleteWorkgroupConfirmation from './DeleteWorkgroupConfirmation';
import WorkgroupBreadcrumb from './WorkgroupBreadcrumb';
import WorkgroupManagement from './WorkgroupManagement';
import type { WorkgroupResponse } from '../services/workgroupApi';

/**
 * Enhanced Workgroup Management Component with Hierarchy Support
 * Feature 040: Nested Workgroups
 *
 * Combines existing flat workgroup management with new hierarchy features:
 * - Tree view with expand/collapse
 * - Breadcrumb navigation
 * - Create child workgroups
 * - Move workgroups
 * - Delete with child promotion
 *
 * Maintains backward compatibility with existing user/asset assignment features.
 */

const WorkgroupManagementWithHierarchy: React.FC = () => {
  const [viewMode, setViewMode] = useState<'tree' | 'table'>('tree');
  const [selectedWorkgroup, setSelectedWorkgroup] = useState<WorkgroupResponse | null>(null);
  const [showCreateChildModal, setShowCreateChildModal] = useState(false);
  const [parentForChild, setParentForChild] = useState<WorkgroupResponse | null>(null);
  const [showMoveModal, setShowMoveModal] = useState(false);
  const [workgroupToMove, setWorkgroupToMove] = useState<WorkgroupResponse | null>(null);
  const [showDeleteModal, setShowDeleteModal] = useState(false);
  const [workgroupToDelete, setWorkgroupToDelete] = useState<WorkgroupResponse | null>(null);
  const [refreshTrigger, setRefreshTrigger] = useState(0);

  const handleSelectWorkgroup = (workgroup: WorkgroupResponse) => {
    setSelectedWorkgroup(workgroup);
  };

  const handleCreateChild = (parentId: number) => {
    // Find the parent workgroup to pass to modal
    // Note: In a real implementation, you might want to fetch this from the API
    // For now, we'll use the selected workgroup if it matches
    if (selectedWorkgroup && selectedWorkgroup.id === parentId) {
      setParentForChild(selectedWorkgroup);
    } else {
      // Create a minimal parent object
      setParentForChild({
        id: parentId,
        name: 'Parent',
        depth: 1,
        childCount: 0,
        hasChildren: false,
        ancestors: [],
        createdAt: '',
        updatedAt: '',
        version: 0
      } as WorkgroupResponse);
    }
    setShowCreateChildModal(true);
  };

  const handleChildCreated = (child: WorkgroupResponse) => {
    setRefreshTrigger(prev => prev + 1); // Trigger tree refresh
    setSelectedWorkgroup(child); // Select the newly created child
  };

  const handleMoveWorkgroup = (workgroup: WorkgroupResponse) => {
    setWorkgroupToMove(workgroup);
    setShowMoveModal(true);
  };

  const handleWorkgroupMoved = (movedWorkgroup: WorkgroupResponse) => {
    setRefreshTrigger(prev => prev + 1); // Trigger tree refresh
    setSelectedWorkgroup(movedWorkgroup); // Select the moved workgroup
  };

  const handleDeleteWorkgroup = (workgroup: WorkgroupResponse) => {
    setWorkgroupToDelete(workgroup);
    setShowDeleteModal(true);
  };

  const handleWorkgroupDeleted = () => {
    setRefreshTrigger(prev => prev + 1); // Trigger tree refresh
    setSelectedWorkgroup(null); // Clear selection
  };

  return (
    <div className="container-fluid mt-4">
      {/* Header with view toggle */}
      <div className="d-flex justify-content-between align-items-center mb-4">
        <h2>
          <i className="bi bi-diagram-3 me-2"></i>
          Workgroup Management
        </h2>
        <div className="btn-group" role="group">
          <button
            type="button"
            className={`btn ${viewMode === 'tree' ? 'btn-primary' : 'btn-outline-primary'}`}
            onClick={() => setViewMode('tree')}
          >
            <i className="bi bi-diagram-3"></i> Tree View
          </button>
          <button
            type="button"
            className={`btn ${viewMode === 'table' ? 'btn-primary' : 'btn-outline-primary'}`}
            onClick={() => setViewMode('table')}
          >
            <i className="bi bi-table"></i> Table View
          </button>
        </div>
      </div>

      {/* Tree View */}
      {viewMode === 'tree' && (
        <div className="row">
          {/* Left Panel: Tree */}
          <div className="col-md-5">
            <div className="card">
              <div className="card-body">
                <WorkgroupTree
                  key={refreshTrigger} // Force refresh when trigger changes
                  onSelectWorkgroup={handleSelectWorkgroup}
                  onCreateChild={handleCreateChild}
                  selectedWorkgroupId={selectedWorkgroup?.id}
                />
              </div>
            </div>
          </div>

          {/* Right Panel: Selected Workgroup Details */}
          <div className="col-md-7">
            {selectedWorkgroup ? (
              <div className="card">
                <div className="card-header bg-primary text-white">
                  <h5 className="mb-0">
                    <i className="bi bi-folder2-open me-2"></i>
                    Workgroup Details
                  </h5>
                </div>
                <div className="card-body">
                  {/* Breadcrumb */}
                  <WorkgroupBreadcrumb
                    workgroup={selectedWorkgroup}
                    onNavigate={(id) => {
                      // Navigate to workgroup by ID
                      // In a real app, you'd fetch the workgroup and select it
                      console.log('Navigate to workgroup:', id);
                    }}
                  />

                  <hr />

                  {/* Workgroup Information */}
                  <div className="row mb-3">
                    <div className="col-md-6">
                      <h4>{selectedWorkgroup.name}</h4>
                      {selectedWorkgroup.description && (
                        <p className="text-muted">{selectedWorkgroup.description}</p>
                      )}
                    </div>
                    <div className="col-md-6 text-end">
                      <div className="mb-2">
                        <span className="badge bg-secondary me-2">
                          Level {selectedWorkgroup.depth} / 5
                        </span>
                        {selectedWorkgroup.hasChildren && (
                          <span className="badge bg-info">
                            {selectedWorkgroup.childCount} child{selectedWorkgroup.childCount === 1 ? '' : 'ren'}
                          </span>
                        )}
                      </div>
                    </div>
                  </div>

                  {/* Hierarchy Actions */}
                  <div className="mb-3">
                    <h6 className="text-muted">Hierarchy Actions</h6>
                    <div className="btn-group btn-group-sm">
                      {selectedWorkgroup.depth < 5 && (
                        <button
                          className="btn btn-outline-primary"
                          onClick={() => handleCreateChild(selectedWorkgroup.id)}
                        >
                          <i className="bi bi-plus-circle me-1"></i>
                          Add Child
                        </button>
                      )}
                      <button
                        className="btn btn-outline-secondary"
                        onClick={() => handleMoveWorkgroup(selectedWorkgroup)}
                      >
                        <i className="bi bi-arrows-move me-1"></i>
                        Move
                      </button>
                      <button
                        className="btn btn-outline-danger"
                        onClick={() => handleDeleteWorkgroup(selectedWorkgroup)}
                      >
                        <i className="bi bi-trash me-1"></i>
                        Delete
                      </button>
                    </div>
                  </div>

                  {/* Metadata */}
                  <div className="border-top pt-3">
                    <h6 className="text-muted">Metadata</h6>
                    <dl className="row mb-0 small">
                      <dt className="col-sm-4">ID</dt>
                      <dd className="col-sm-8">{selectedWorkgroup.id}</dd>

                      <dt className="col-sm-4">Parent ID</dt>
                      <dd className="col-sm-8">
                        {selectedWorkgroup.parentId ?? <span className="text-muted">None (Root level)</span>}
                      </dd>

                      <dt className="col-sm-4">Created</dt>
                      <dd className="col-sm-8">
                        {new Date(selectedWorkgroup.createdAt).toLocaleString()}
                      </dd>

                      <dt className="col-sm-4">Updated</dt>
                      <dd className="col-sm-8">
                        {new Date(selectedWorkgroup.updatedAt).toLocaleString()}
                      </dd>

                      <dt className="col-sm-4">Version</dt>
                      <dd className="col-sm-8">
                        {selectedWorkgroup.version} <span className="text-muted">(optimistic locking)</span>
                      </dd>
                    </dl>
                  </div>

                  {/* Ancestor Path */}
                  {selectedWorkgroup.ancestors.length > 0 && (
                    <div className="border-top pt-3 mt-3">
                      <h6 className="text-muted">Ancestor Path</h6>
                      <div className="d-flex flex-wrap gap-2">
                        {selectedWorkgroup.ancestors.map((ancestor, index) => (
                          <React.Fragment key={ancestor.id}>
                            <span className="badge bg-light text-dark border">
                              {ancestor.name}
                            </span>
                            {index < selectedWorkgroup.ancestors.length - 1 && (
                              <i className="bi bi-chevron-right text-muted"></i>
                            )}
                          </React.Fragment>
                        ))}
                      </div>
                    </div>
                  )}
                </div>
              </div>
            ) : (
              <div className="card">
                <div className="card-body text-center py-5 text-muted">
                  <i className="bi bi-folder2-open" style={{ fontSize: '48px' }}></i>
                  <p className="mt-3">Select a workgroup from the tree to view details</p>
                </div>
              </div>
            )}
          </div>
        </div>
      )}

      {/* Table View (Existing Flat View) */}
      {viewMode === 'table' && (
        <div className="alert alert-info mb-3">
          <i className="bi bi-info-circle"></i>
          <strong> Table View:</strong> This is the classic flat view of all workgroups. Switch to Tree View to see the hierarchy.
        </div>
      )}
      {viewMode === 'table' && <WorkgroupManagement />}

      {/* Modals */}
      <CreateChildWorkgroupModal
        show={showCreateChildModal}
        parentWorkgroup={parentForChild}
        onClose={() => {
          setShowCreateChildModal(false);
          setParentForChild(null);
        }}
        onSuccess={handleChildCreated}
      />

      <MoveWorkgroupModal
        show={showMoveModal}
        workgroup={workgroupToMove}
        onClose={() => {
          setShowMoveModal(false);
          setWorkgroupToMove(null);
        }}
        onSuccess={handleWorkgroupMoved}
      />

      <DeleteWorkgroupConfirmation
        show={showDeleteModal}
        workgroup={workgroupToDelete}
        onClose={() => {
          setShowDeleteModal(false);
          setWorkgroupToDelete(null);
        }}
        onSuccess={handleWorkgroupDeleted}
      />
    </div>
  );
};

export default WorkgroupManagementWithHierarchy;
