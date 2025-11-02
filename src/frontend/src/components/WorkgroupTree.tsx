import React, { useState, useEffect } from 'react';
import type { WorkgroupResponse } from '../services/workgroupApi';
import { getWorkgroupChildren, getRootWorkgroups } from '../services/workgroupApi';

/**
 * Workgroup Tree Component
 * Feature 040: Nested Workgroups (User Story 2 & 5)
 *
 * Displays hierarchical workgroup tree with:
 * - Expand/collapse functionality
 * - Lazy loading of children
 * - Breadcrumb navigation
 * - Visual depth indicators
 */

interface WorkgroupTreeProps {
  onSelectWorkgroup?: (workgroup: WorkgroupResponse) => void;
  onCreateChild?: (parentId: number) => void;
  selectedWorkgroupId?: number | null;
}

interface TreeNodeProps {
  workgroup: WorkgroupResponse;
  level: number;
  onSelectWorkgroup?: (workgroup: WorkgroupResponse) => void;
  onCreateChild?: (parentId: number) => void;
  selectedWorkgroupId?: number | null;
}

const TreeNode: React.FC<TreeNodeProps> = ({
  workgroup,
  level,
  onSelectWorkgroup,
  onCreateChild,
  selectedWorkgroupId
}) => {
  const [isExpanded, setIsExpanded] = useState(false);
  const [children, setChildren] = useState<WorkgroupResponse[]>([]);
  const [loadingChildren, setLoadingChildren] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const hasChildren = workgroup.hasChildren || workgroup.childCount > 0;
  const isSelected = selectedWorkgroupId === workgroup.id;

  const toggleExpand = async () => {
    if (!hasChildren) return;

    if (!isExpanded && children.length === 0) {
      // Lazy load children
      setLoadingChildren(true);
      setError(null);
      try {
        const childWorkgroups = await getWorkgroupChildren(workgroup.id);
        setChildren(childWorkgroups);
        setIsExpanded(true);
      } catch (err) {
        setError(err instanceof Error ? err.message : 'Failed to load children');
        console.error('Failed to load children:', err);
      } finally {
        setLoadingChildren(false);
      }
    } else {
      setIsExpanded(!isExpanded);
    }
  };

  const handleSelect = () => {
    if (onSelectWorkgroup) {
      onSelectWorkgroup(workgroup);
    }
  };

  const handleCreateChild = (e: React.MouseEvent) => {
    e.stopPropagation();
    if (onCreateChild) {
      onCreateChild(workgroup.id);
    }
  };

  const indentStyle = {
    marginLeft: `${level * 20}px`
  };

  return (
    <div>
      <div
        className={`d-flex align-items-center py-2 px-3 border-bottom ${isSelected ? 'bg-primary bg-opacity-10' : 'hover-bg-light'}`}
        style={{ ...indentStyle, cursor: 'pointer' }}
        onClick={handleSelect}
      >
        {/* Expand/collapse icon */}
        <span
          className="me-2"
          style={{ width: '20px', cursor: hasChildren ? 'pointer' : 'default' }}
          onClick={(e) => {
            e.stopPropagation();
            toggleExpand();
          }}
        >
          {loadingChildren ? (
            <i className="bi bi-hourglass-split text-muted"></i>
          ) : hasChildren ? (
            isExpanded ? (
              <i className="bi bi-chevron-down"></i>
            ) : (
              <i className="bi bi-chevron-right"></i>
            )
          ) : (
            <i className="bi bi-circle-fill" style={{ fontSize: '6px' }}></i>
          )}
        </span>

        {/* Workgroup icon */}
        <i className="bi bi-folder2 me-2 text-primary"></i>

        {/* Workgroup name */}
        <span className="flex-grow-1 fw-medium">{workgroup.name}</span>

        {/* Depth badge */}
        <span className="badge bg-secondary me-2">
          L{workgroup.depth}
        </span>

        {/* Child count */}
        {hasChildren && (
          <span className="badge bg-info me-2">
            {workgroup.childCount} {workgroup.childCount === 1 ? 'child' : 'children'}
          </span>
        )}

        {/* Add child button */}
        {workgroup.depth < 5 && onCreateChild && (
          <button
            className="btn btn-sm btn-outline-primary"
            onClick={handleCreateChild}
            title="Add child workgroup"
          >
            <i className="bi bi-plus-circle"></i>
          </button>
        )}
      </div>

      {/* Error message */}
      {error && (
        <div className="alert alert-danger mx-3 my-2" style={indentStyle}>
          <i className="bi bi-exclamation-triangle"></i> {error}
        </div>
      )}

      {/* Children */}
      {isExpanded && children.length > 0 && (
        <div>
          {children.map((child) => (
            <TreeNode
              key={child.id}
              workgroup={child}
              level={level + 1}
              onSelectWorkgroup={onSelectWorkgroup}
              onCreateChild={onCreateChild}
              selectedWorkgroupId={selectedWorkgroupId}
            />
          ))}
        </div>
      )}
    </div>
  );
};

const WorkgroupTree: React.FC<WorkgroupTreeProps> = ({
  onSelectWorkgroup,
  onCreateChild,
  selectedWorkgroupId
}) => {
  const [rootWorkgroups, setRootWorkgroups] = useState<WorkgroupResponse[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    fetchRootWorkgroups();
  }, []);

  const fetchRootWorkgroups = async () => {
    setLoading(true);
    setError(null);
    try {
      const roots = await getRootWorkgroups();
      setRootWorkgroups(roots);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to load workgroups');
      console.error('Failed to load root workgroups:', err);
    } finally {
      setLoading(false);
    }
  };

  if (loading) {
    return (
      <div className="text-center py-5">
        <div className="spinner-border text-primary" role="status">
          <span className="visually-hidden">Loading...</span>
        </div>
        <p className="text-muted mt-2">Loading workgroup tree...</p>
      </div>
    );
  }

  if (error) {
    return (
      <div className="alert alert-danger">
        <i className="bi bi-exclamation-triangle"></i> {error}
        <button
          className="btn btn-sm btn-outline-danger ms-3"
          onClick={fetchRootWorkgroups}
        >
          Retry
        </button>
      </div>
    );
  }

  if (rootWorkgroups.length === 0) {
    return (
      <div className="text-center py-5 text-muted">
        <i className="bi bi-folder2-open" style={{ fontSize: '48px' }}></i>
        <p className="mt-3">No workgroups found</p>
        <p className="small">Create a root-level workgroup to get started</p>
      </div>
    );
  }

  return (
    <div className="workgroup-tree border rounded">
      <div className="bg-light px-3 py-2 border-bottom">
        <h6 className="mb-0">
          <i className="bi bi-diagram-3"></i> Workgroup Hierarchy
        </h6>
      </div>
      <div className="tree-content">
        {rootWorkgroups.map((workgroup) => (
          <TreeNode
            key={workgroup.id}
            workgroup={workgroup}
            level={0}
            onSelectWorkgroup={onSelectWorkgroup}
            onCreateChild={onCreateChild}
            selectedWorkgroupId={selectedWorkgroupId}
          />
        ))}
      </div>
    </div>
  );
};

export default WorkgroupTree;
