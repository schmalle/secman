import React, { useCallback, useEffect, useRef, useState } from 'react';
import userProfileService from '../services/userProfileService';

/**
 * Profile Picture Card
 * Feature: Profile Picture Management
 *
 * Lets the signed-in user upload, crop, replace and remove their own avatar.
 *
 * The crop step is hand-rolled on a canvas rather than pulling in a cropping library - the repo
 * carries no such dependency and the interaction needed here (pan + zoom inside a square) is
 * small. Saving redraws the visible square into an offscreen canvas and exports a PNG blob, so
 * whatever the browser can decode (including WEBP and HEIC) reaches the server as a PNG, even
 * though the server's own allowlist is PNG/JPEG/GIF.
 */

const MAX_SOURCE_BYTES = 10 * 1024 * 1024; // pre-decode guard; the cropped upload is far smaller
const OUTPUT_EDGE = 512;                   // server downscales to its configured target
const VIEWPORT_EDGE = 320;                 // on-screen cropper size
const MAX_ZOOM = 4;

interface ProfilePictureCardProps {
    initialHasPicture: boolean;
    initialUpdatedAt: string | null;
}

export default function ProfilePictureCard({ initialHasPicture, initialUpdatedAt }: ProfilePictureCardProps) {
    const [hasPicture, setHasPicture] = useState(initialHasPicture);
    const [updatedAt, setUpdatedAt] = useState<string | null>(initialUpdatedAt);
    const [error, setError] = useState<string | null>(null);
    const [success, setSuccess] = useState<string | null>(null);
    const [busy, setBusy] = useState(false);
    const [isDragOver, setIsDragOver] = useState(false);
    const [previewFailed, setPreviewFailed] = useState(false);

    // Cropper state
    const [sourceImage, setSourceImage] = useState<HTMLImageElement | null>(null);
    const [zoom, setZoom] = useState(1);
    const [offset, setOffset] = useState({ x: 0, y: 0 });

    const fileInputRef = useRef<HTMLInputElement>(null);
    const canvasRef = useRef<HTMLCanvasElement>(null);
    const dragState = useRef<{ active: boolean; startX: number; startY: number; originX: number; originY: number }>({
        active: false, startX: 0, startY: 0, originX: 0, originY: 0
    });

    useEffect(() => {
        setPreviewFailed(false);
    }, [updatedAt, hasPicture]);

    /**
     * The scale at which the image exactly covers the square viewport. All zooming is expressed
     * as a multiple of this, so the crop can never expose empty canvas.
     */
    const coverScale = useCallback((image: HTMLImageElement) => {
        return Math.max(VIEWPORT_EDGE / image.naturalWidth, VIEWPORT_EDGE / image.naturalHeight);
    }, []);

    /** Keep the image covering the viewport after any pan or zoom. */
    const clampOffset = useCallback((next: { x: number; y: number }, image: HTMLImageElement, scale: number) => {
        const drawnWidth = image.naturalWidth * scale;
        const drawnHeight = image.naturalHeight * scale;
        const maxX = Math.max(0, (drawnWidth - VIEWPORT_EDGE) / 2);
        const maxY = Math.max(0, (drawnHeight - VIEWPORT_EDGE) / 2);
        return {
            x: Math.min(maxX, Math.max(-maxX, next.x)),
            y: Math.min(maxY, Math.max(-maxY, next.y))
        };
    }, []);

    /** Draw the image into a square context at the current pan/zoom, scaled to `edge`. */
    const paint = useCallback((
        ctx: CanvasRenderingContext2D,
        image: HTMLImageElement,
        edge: number
    ) => {
        const ratio = edge / VIEWPORT_EDGE;
        const scale = coverScale(image) * zoom * ratio;
        const drawnWidth = image.naturalWidth * scale;
        const drawnHeight = image.naturalHeight * scale;
        const x = (edge - drawnWidth) / 2 + offset.x * ratio;
        const y = (edge - drawnHeight) / 2 + offset.y * ratio;

        ctx.clearRect(0, 0, edge, edge);
        ctx.fillStyle = '#ffffff';
        ctx.fillRect(0, 0, edge, edge);
        ctx.imageSmoothingEnabled = true;
        ctx.imageSmoothingQuality = 'high';
        ctx.drawImage(image, x, y, drawnWidth, drawnHeight);
    }, [coverScale, zoom, offset]);

    // Repaint the on-screen cropper whenever the image, zoom or pan changes.
    useEffect(() => {
        const canvas = canvasRef.current;
        if (!canvas || !sourceImage) return;
        const ctx = canvas.getContext('2d');
        if (!ctx) return;
        paint(ctx, sourceImage, VIEWPORT_EDGE);
    }, [sourceImage, paint]);

    const resetCropper = () => {
        setSourceImage(null);
        setZoom(1);
        setOffset({ x: 0, y: 0 });
        if (fileInputRef.current) {
            // Reset so re-picking the same file still fires onChange.
            fileInputRef.current.value = '';
        }
    };

    const validateFile = (file: File): string | null => {
        if (file.size > MAX_SOURCE_BYTES) {
            return 'Image must be 10 MB or smaller';
        }
        if (!file.type.startsWith('image/')) {
            return 'Please choose an image file';
        }
        if (file.type === 'image/svg+xml') {
            return 'SVG images are not supported';
        }
        return null;
    };

    const openCropper = (file: File) => {
        setError(null);
        setSuccess(null);

        const validationError = validateFile(file);
        if (validationError) {
            setError(validationError);
            if (fileInputRef.current) fileInputRef.current.value = '';
            return;
        }

        const objectUrl = URL.createObjectURL(file);
        const image = new Image();
        image.onload = () => {
            URL.revokeObjectURL(objectUrl);
            setZoom(1);
            setOffset({ x: 0, y: 0 });
            setSourceImage(image);
        };
        image.onerror = () => {
            URL.revokeObjectURL(objectUrl);
            setError('That file could not be read as an image');
            if (fileInputRef.current) fileInputRef.current.value = '';
        };
        image.src = objectUrl;
    };

    const handleFileChange = (event: React.ChangeEvent<HTMLInputElement>) => {
        const file = event.target.files?.[0];
        if (file) openCropper(file);
    };

    const handleDragOver = (event: React.DragEvent) => {
        event.preventDefault();
        setIsDragOver(true);
    };

    const handleDragLeave = (event: React.DragEvent) => {
        event.preventDefault();
        setIsDragOver(false);
    };

    const handleDrop = (event: React.DragEvent) => {
        event.preventDefault();
        setIsDragOver(false);
        const file = event.dataTransfer.files?.[0];
        if (file) openCropper(file);
    };

    const handlePointerDown = (event: React.PointerEvent<HTMLCanvasElement>) => {
        if (!sourceImage) return;
        event.currentTarget.setPointerCapture(event.pointerId);
        dragState.current = {
            active: true,
            startX: event.clientX,
            startY: event.clientY,
            originX: offset.x,
            originY: offset.y
        };
    };

    const handlePointerMove = (event: React.PointerEvent<HTMLCanvasElement>) => {
        if (!dragState.current.active || !sourceImage) return;
        const next = {
            x: dragState.current.originX + (event.clientX - dragState.current.startX),
            y: dragState.current.originY + (event.clientY - dragState.current.startY)
        };
        setOffset(clampOffset(next, sourceImage, coverScale(sourceImage) * zoom));
    };

    const handlePointerUp = (event: React.PointerEvent<HTMLCanvasElement>) => {
        dragState.current.active = false;
        if (event.currentTarget.hasPointerCapture(event.pointerId)) {
            event.currentTarget.releasePointerCapture(event.pointerId);
        }
    };

    const applyZoom = (nextZoom: number) => {
        if (!sourceImage) return;
        const clamped = Math.min(MAX_ZOOM, Math.max(1, nextZoom));
        setZoom(clamped);
        setOffset((current) => clampOffset(current, sourceImage, coverScale(sourceImage) * clamped));
    };

    const handleWheel = (event: React.WheelEvent<HTMLCanvasElement>) => {
        if (!sourceImage) return;
        applyZoom(zoom * (event.deltaY < 0 ? 1.1 : 1 / 1.1));
    };

    const notifyHeader = (nextHasPicture: boolean, nextUpdatedAt: string | null) => {
        // Patch the cached session payload and re-dispatch the event Header.tsx already listens
        // for, so the avatar in the navbar updates without a page reload.
        try {
            if (window.currentUser) {
                window.currentUser = {
                    ...window.currentUser,
                    hasProfilePicture: nextHasPicture,
                    profilePictureUpdatedAt: nextUpdatedAt
                };
            }
            const stored = sessionStorage.getItem('user');
            if (stored) {
                const parsed = JSON.parse(stored);
                parsed.hasProfilePicture = nextHasPicture;
                parsed.profilePictureUpdatedAt = nextUpdatedAt;
                sessionStorage.setItem('user', JSON.stringify(parsed));
            }
            window.dispatchEvent(new CustomEvent('userLoaded'));
        } catch {
            // A stale header is not worth failing the save over - the next page load corrects it.
        }
    };

    const handleSave = async () => {
        if (!sourceImage) return;

        const canvas = document.createElement('canvas');
        canvas.width = OUTPUT_EDGE;
        canvas.height = OUTPUT_EDGE;
        const ctx = canvas.getContext('2d');
        if (!ctx) {
            setError('Your browser could not process the image');
            return;
        }
        paint(ctx, sourceImage, OUTPUT_EDGE);

        setBusy(true);
        setError(null);
        setSuccess(null);
        try {
            const blob = await new Promise<Blob | null>((resolve) => {
                canvas.toBlob(resolve, 'image/png');
            });
            if (!blob) {
                setError('Your browser could not process the image');
                return;
            }

            const metadata = await userProfileService.uploadProfilePicture(blob);
            const nextUpdatedAt = metadata.updatedAt ?? new Date().toISOString();
            setHasPicture(true);
            setUpdatedAt(nextUpdatedAt);
            setSuccess('Profile picture updated');
            notifyHeader(true, nextUpdatedAt);
            resetCropper();
        } catch (err: any) {
            setError(err.response?.data?.message || 'Failed to upload the profile picture');
        } finally {
            setBusy(false);
        }
    };

    const handleRemove = async () => {
        if (!window.confirm('Remove your profile picture?')) return;

        setBusy(true);
        setError(null);
        setSuccess(null);
        try {
            await userProfileService.deleteProfilePicture();
            setHasPicture(false);
            setUpdatedAt(null);
            setSuccess('Profile picture removed');
            notifyHeader(false, null);
        } catch (err: any) {
            setError(err.response?.data?.message || 'Failed to remove the profile picture');
        } finally {
            setBusy(false);
        }
    };

    const showPreview = hasPicture && !previewFailed;

    return (
        <div className="card mt-3">
            <div className="card-body">
                <h5 className="card-title">Profile Picture</h5>
                <p className="text-muted small">
                    Shown next to your name in the navigation bar. PNG, JPEG or GIF, up to 10 MB.
                </p>

                {error && (
                    <div className="alert alert-danger" role="alert">
                        <i className="bi bi-exclamation-triangle-fill me-2"></i>{error}
                    </div>
                )}
                {success && (
                    <div className="alert alert-success" role="alert">
                        <i className="bi bi-check-circle-fill me-2"></i>{success}
                    </div>
                )}

                {!sourceImage ? (
                    <div className="d-flex align-items-center gap-4 flex-wrap">
                        <div style={{ width: '128px', height: '128px', flexShrink: 0 }}>
                            {showPreview ? (
                                <img
                                    src={userProfileService.profilePictureUrl(updatedAt)}
                                    alt="Your profile picture"
                                    className="rounded-circle border"
                                    style={{ width: '128px', height: '128px', objectFit: 'cover' }}
                                    onError={() => setPreviewFailed(true)}
                                />
                            ) : (
                                <i
                                    className="bi bi-person-circle"
                                    style={{ fontSize: '8rem', lineHeight: 1, color: 'var(--scand-text-secondary)' }}
                                    aria-hidden="true"
                                ></i>
                            )}
                        </div>

                        <div
                            className={`flex-grow-1 border rounded p-4 text-center ${isDragOver ? 'border-primary bg-light' : 'border-secondary-subtle'}`}
                            onDragOver={handleDragOver}
                            onDragLeave={handleDragLeave}
                            onDrop={handleDrop}
                            style={{ minWidth: '260px', borderStyle: 'dashed' }}
                        >
                            <i className="bi bi-cloud-arrow-up fs-3 d-block mb-2 text-secondary"></i>
                            <p className="mb-2 small text-muted">Drag an image here, or</p>
                            <input
                                ref={fileInputRef}
                                type="file"
                                className="d-none"
                                accept="image/png,image/jpeg,image/gif"
                                onChange={handleFileChange}
                            />
                            <button
                                type="button"
                                className="btn btn-outline-primary btn-sm me-2"
                                onClick={() => fileInputRef.current?.click()}
                                disabled={busy}
                            >
                                <i className="bi bi-upload me-1"></i>
                                {hasPicture ? 'Change picture' : 'Upload picture'}
                            </button>
                            {hasPicture && (
                                <button
                                    type="button"
                                    className="btn btn-outline-danger btn-sm"
                                    onClick={handleRemove}
                                    disabled={busy}
                                >
                                    {busy ? (
                                        <span className="spinner-border spinner-border-sm me-2" role="status" aria-hidden="true"></span>
                                    ) : (
                                        <i className="bi bi-trash me-1"></i>
                                    )}
                                    Remove
                                </button>
                            )}
                        </div>
                    </div>
                ) : (
                    <div>
                        <p className="small text-muted mb-2">
                            Drag to reposition, scroll or use the slider to zoom.
                        </p>
                        <canvas
                            ref={canvasRef}
                            width={VIEWPORT_EDGE}
                            height={VIEWPORT_EDGE}
                            className="border rounded-circle"
                            style={{ cursor: 'grab', touchAction: 'none', maxWidth: '100%' }}
                            onPointerDown={handlePointerDown}
                            onPointerMove={handlePointerMove}
                            onPointerUp={handlePointerUp}
                            onPointerCancel={handlePointerUp}
                            onWheel={handleWheel}
                        />

                        <div className="d-flex align-items-center gap-2 mt-3" style={{ maxWidth: `${VIEWPORT_EDGE}px` }}>
                            <i className="bi bi-zoom-out text-secondary" aria-hidden="true"></i>
                            <input
                                type="range"
                                className="form-range"
                                min={1}
                                max={MAX_ZOOM}
                                step={0.01}
                                value={zoom}
                                onChange={(e) => applyZoom(parseFloat(e.target.value))}
                                aria-label="Zoom"
                            />
                            <i className="bi bi-zoom-in text-secondary" aria-hidden="true"></i>
                        </div>

                        <div className="mt-3">
                            <button
                                type="button"
                                className="btn btn-primary me-2"
                                onClick={handleSave}
                                disabled={busy}
                            >
                                {busy && (
                                    <span className="spinner-border spinner-border-sm me-2" role="status" aria-hidden="true"></span>
                                )}
                                Save picture
                            </button>
                            <button
                                type="button"
                                className="btn btn-outline-secondary"
                                onClick={resetCropper}
                                disabled={busy}
                            >
                                Cancel
                            </button>
                        </div>
                    </div>
                )}
            </div>
        </div>
    );
}
