import { WORKSPACE_LABELS, type Workspace } from "../lib/workspaces";

/** Shown to users with more than one workspace (e.g. a customer who is also an admin). */
export function WorkspaceSwitcher({
  workspaces,
  active,
  onChange,
}: {
  workspaces: readonly Workspace[];
  active: Workspace;
  onChange: (workspace: Workspace) => void;
}) {
  if (workspaces.length < 2) {
    return null;
  }

  return (
    <nav className="workspace-switcher" aria-label="Workspaces">
      {workspaces.map((workspace) => (
        <button
          key={workspace}
          className={workspace === active ? "active" : ""}
          type="button"
          aria-pressed={workspace === active}
          onClick={() => onChange(workspace)}
        >
          {WORKSPACE_LABELS[workspace]}
        </button>
      ))}
    </nav>
  );
}
