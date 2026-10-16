import type { CurrentUser } from "../api/dto";
import type { SessionStatus } from "../hooks/useSession";

const sessionLabel = (user: CurrentUser | null, status: SessionStatus) => {
  if (user) {
    return `${user.first_name || user.email} is signed in`;
  }
  if (status === "loading") {
    return "Restoring session";
  }
  if (status === "expired") {
    return "Session expired";
  }
  return "Guest browsing";
};

interface TopBarProps {
  user: CurrentUser | null;
  status: SessionStatus;
  onLogout: () => void;
}

export function TopBar({ user, status, onLogout }: TopBarProps) {
  return (
    <header className="topbar">
      <div className="brand-block">
        <div className="brand-icon" aria-hidden="true">
          LF
        </div>
        <div>
          <p className="brand-name">Luc1fer&apos;s Food Bank</p>
          <h1>Operational food ordering MVP</h1>
        </div>
      </div>

      <div className="session-strip" aria-live="polite">
        <span className={`status-dot status-dot--${user ? "ok" : "muted"}`} />
        <div>
          <strong>{sessionLabel(user, status)}</strong>
          <span>{user ? user.email : "Public restaurant and menu browsing stays available."}</span>
        </div>
        {user ? (
          <button className="ghost-button" type="button" onClick={onLogout}>
            Logout
          </button>
        ) : null}
      </div>
    </header>
  );
}
