/**
 * Toast Notification Component
 *
 * Simple Bootstrap-based toast notification for success/error messages
 *
 * Related to: Feature 012-build-ui-for
 */

import React, { useEffect } from 'react';

export interface ToastProps {
    message: string;
    type: 'success' | 'error' | 'warning' | 'info';
    show: boolean;
    onClose: () => void;
    duration?: number; // Auto-dismiss after X milliseconds
}

const Toast: React.FC<ToastProps> = ({ message, type, show, onClose, duration = 5000 }) => {
    useEffect(() => {
        if (show && duration > 0) {
            const timer = setTimeout(() => {
                onClose();
            }, duration);

            return () => clearTimeout(timer);
        }
    }, [show, duration, onClose]);

    if (!show) {
        return null;
    }

    const bgClass = {
        success: 'bg-success',
        error: 'bg-danger',
        warning: 'bg-warning',
        info: 'bg-info',
    }[type];

    const icon = {
        success: 'bi-check-circle-fill',
        error: 'bi-x-circle-fill',
        warning: 'bi-exclamation-triangle-fill',
        info: 'bi-info-circle-fill',
    }[type];

    return (
        <div
            className="position-fixed top-0 end-0 p-3"
            style={{ zIndex: 9999 }}
        >
            <div className={`toast show ${type}`} role="alert" aria-live="assertive" aria-atomic="true">
                <div className={`toast-header ${bgClass} text-white`}>
                    <i className={`bi ${icon} me-2`}></i>
                    <strong className="me-auto">
                        {type === 'success' && 'Success'}
                        {type === 'error' && 'Error'}
                        {type === 'warning' && 'Warning'}
                        {type === 'info' && 'Info'}
                    </strong>
                    <button
                        type="button"
                        className="btn-close btn-close-white"
                        aria-label="Close"
                        onClick={onClose}
                    ></button>
                </div>
                <div className="toast-body">
                    {message}
                </div>
            </div>
        </div>
    );
};

export default Toast;
