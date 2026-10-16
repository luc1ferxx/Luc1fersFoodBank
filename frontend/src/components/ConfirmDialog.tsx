import { type ReactNode, useEffect, useEffectEvent, useId, useRef } from "react";
import { createPortal } from "react-dom";
import { Alert } from "./Alert";

interface ConfirmDialogProps {
  title: string;
  children: ReactNode;
  confirmLabel: string;
  pendingLabel: string;
  cancelLabel?: string;
  pending: boolean;
  /** Disables only the confirm button, e.g. while another action must finish first. */
  confirmDisabled?: boolean;
  error: string;
  onConfirm: () => void;
  onCancel: () => void;
}

const FOCUSABLE = 'button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])';

const focusableIn = (container: HTMLElement): HTMLElement[] =>
  Array.from(container.querySelectorAll<HTMLElement>(FOCUSABLE)).filter(
    (element) => !element.hasAttribute("disabled"),
  );

/**
 * A modal confirmation, rendered into document.body. While it is open the rest of
 * the page is `inert` (no focus, clicks, or screen-reader browsing), Tab and
 * Shift+Tab cycle through the dialog's own controls, and Escape cancels from
 * anywhere unless the action is running. Focus starts on Cancel and returns to the
 * previously focused element when the dialog closes.
 */
export function ConfirmDialog({
  title,
  children,
  confirmLabel,
  pendingLabel,
  cancelLabel = "Cancel",
  pending,
  confirmDisabled = false,
  error,
  onConfirm,
  onCancel,
}: ConfirmDialogProps) {
  const titleId = useId();
  const bodyId = useId();
  const backdropRef = useRef<HTMLDivElement>(null);
  const dialogRef = useRef<HTMLDivElement>(null);
  const cancelRef = useRef<HTMLButtonElement>(null);

  const handleKeyDown = useEffectEvent((event: KeyboardEvent) => {
    const dialog = dialogRef.current;
    if (!dialog) {
      return;
    }
    if (event.key === "Escape") {
      if (!pending) {
        event.preventDefault();
        onCancel();
      }
      return;
    }
    if (event.key !== "Tab") {
      return;
    }

    const focusable = focusableIn(dialog);
    const first = focusable[0];
    const last = focusable.at(-1);
    if (!first || !last) {
      // Every control is disabled while the action runs: keep focus on the dialog.
      event.preventDefault();
      dialog.focus();
      return;
    }
    const current = document.activeElement;
    const inside = current !== null && current !== dialog && dialog.contains(current);
    const leaving = event.shiftKey ? !inside || current === first : !inside || current === last;
    if (leaving) {
      event.preventDefault();
      (event.shiftKey ? last : first).focus();
    }
  });

  useEffect(() => {
    const backdrop = backdropRef.current;
    const previouslyFocused = document.activeElement;
    // The rest of the page (the app root, among others) cannot be reached while open.
    const background = Array.from(document.body.children).filter(
      (element) => element !== backdrop && !element.hasAttribute("inert"),
    );
    background.forEach((element) => element.setAttribute("inert", ""));
    cancelRef.current?.focus();

    const onKeyDown = (event: KeyboardEvent) => handleKeyDown(event);
    document.addEventListener("keydown", onKeyDown);
    return () => {
      document.removeEventListener("keydown", onKeyDown);
      // Leave inert first: an inert element cannot take the focus back.
      background.forEach((element) => element.removeAttribute("inert"));
      if (previouslyFocused instanceof HTMLElement) {
        previouslyFocused.focus();
      }
    };
  }, []);

  return createPortal(
    <div ref={backdropRef} className="dialog-backdrop">
      <div
        ref={dialogRef}
        className="dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        aria-describedby={bodyId}
        tabIndex={-1}
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
          <button
            className="primary-button"
            type="button"
            disabled={pending || confirmDisabled}
            onClick={onConfirm}
          >
            {pending ? pendingLabel : confirmLabel}
          </button>
        </div>
      </div>
    </div>,
    document.body,
  );
}
