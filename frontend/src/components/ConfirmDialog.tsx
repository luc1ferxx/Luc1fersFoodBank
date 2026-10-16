import { type ReactNode, useEffect, useId, useRef } from "react";
import { Alert } from "./Alert";

interface ConfirmDialogProps {
  title: string;
  children: ReactNode;
  confirmLabel: string;
  pendingLabel: string;
  cancelLabel?: string;
  pending: boolean;
  error: string;
  onConfirm: () => void;
  onCancel: () => void;
}

/**
 * A small modal confirmation. Focus moves to Cancel when it opens and returns to the
 * previously focused element when it closes; Escape cancels unless the action is
 * running.
 */
export function ConfirmDialog({
  title,
  children,
  confirmLabel,
  pendingLabel,
  cancelLabel = "Cancel",
  pending,
  error,
  onConfirm,
  onCancel,
}: ConfirmDialogProps) {
  const titleId = useId();
  const bodyId = useId();
  const cancelRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    const previouslyFocused = document.activeElement;
    cancelRef.current?.focus();
    return () => {
      if (previouslyFocused instanceof HTMLElement) {
        previouslyFocused.focus();
      }
    };
  }, []);

  return (
    <div className="dialog-backdrop">
      <div
        className="dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        aria-describedby={bodyId}
        onKeyDown={(event) => {
          if (event.key === "Escape" && !pending) {
            event.stopPropagation();
            onCancel();
          }
        }}
      >
        <h2 id={titleId}>{title}</h2>
        <div id={bodyId} className="dialog__body">
          {children}
        </div>
        <Alert tone="danger">{error}</Alert>
        <div className="dialog__actions">
          <button
            ref={cancelRef}
            className="ghost-button"
            type="button"
            disabled={pending}
            onClick={onCancel}
          >
            {cancelLabel}
          </button>
          <button className="primary-button" type="button" disabled={pending} onClick={onConfirm}>
            {pending ? pendingLabel : confirmLabel}
          </button>
        </div>
      </div>
    </div>
  );
}
