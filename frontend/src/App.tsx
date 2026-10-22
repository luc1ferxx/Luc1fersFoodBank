import { useState } from "react";
import "./App.css";
import { ErrorBoundary } from "./components/ErrorBoundary";
import type { OperationsTab } from "./components/OperationsPanel";
import { ServerSignalsProvider } from "./components/ServerSignalsProvider";
import { TopBar } from "./components/TopBar";
import { WorkspaceSwitcher } from "./components/WorkspaceSwitcher";
import { type MenuBrowser, useMenu } from "./hooks/useMenu";
import { type Session, useSession } from "./hooks/useSession";
import {
  WORKSPACE_LABELS,
  type Workspace,
  availableWorkspaces,
  defaultWorkspace,
} from "./lib/workspaces";
import { AdminConsole } from "./pages/AdminConsole";
import { MerchantConsole } from "./pages/MerchantConsole";
import { OrderingPage } from "./pages/OrderingPage";
import { RiderConsole } from "./pages/RiderConsole";

/**
 * Workspaces that stay mounted, hidden, once opened: the ordering page and the admin
 * console hold the Idempotency-Keys of failed checkouts, cancels, and status changes
 * (and half-filled forms), which a retry after switching away and back must reuse.
 * The merchant and rider consoles hold no keys (their commands are idempotent by
 * state) and poll, so they unmount when hidden and read afresh when shown again.
 */
const KEPT_MOUNTED: ReadonlySet<Workspace> = new Set<Workspace>(["order", "admin"]);

interface WorkspacesProps {
  session: Session;
  menu: MenuBrowser;
  searchValue: string;
  onSearchChange: (value: string) => void;
  activePanel: OperationsTab;
  onPanelChange: (panel: OperationsTab) => void;
}

/**
 * The workspace switcher and the workspaces of one signed-in user (or the guest).
 * App keys it by the user, and the user is null in between two sign-ins, so the
 * workspace choice and all session-scoped state start fresh on every login, logout,
 * or session expiry, even when the same user signs in again. That includes the user's
 * one change-signal stream (ServerSignalsProvider): a workspace switch keeps it, a
 * logout or an expiry closes it, and a guest gets none.
 */
function Workspaces({ session, menu, ...ordering }: WorkspacesProps) {
  const { user } = session;
  const workspaces = availableWorkspaces(user);
  const [chosen, setChosen] = useState<Workspace | null>(null);
  const [opened, setOpened] = useState<readonly Workspace[]>([]);
  const active =
    chosen !== null && workspaces.includes(chosen) ? chosen : defaultWorkspace(workspaces);
  const mounted = workspaces.filter(
    (workspace) =>
      workspace === active || (KEPT_MOUNTED.has(workspace) && opened.includes(workspace)),
  );

  const switchTo = (next: Workspace) => {
    setOpened((current) => [...new Set([...current, active, next])]);
    setChosen(next);
  };

  const content = (workspace: Workspace) => {
    switch (workspace) {
      case "order":
        return (
          <OrderingPage session={session} menu={menu} active={active === "order"} {...ordering} />
        );
      case "merchant":
        return user ? <MerchantConsole user={user} /> : null;
      case "rider":
        return user ? <RiderConsole user={user} /> : null;
      case "admin":
        return user ? <AdminConsole menu={menu} /> : null;
    }
  };

  return (
    <ServerSignalsProvider signedIn={user !== null}>
      <WorkspaceSwitcher workspaces={workspaces} active={active} onChange={switchTo} />
      {mounted.map((workspace) => (
        <div key={workspace} hidden={workspace !== active}>
          <ErrorBoundary label={WORKSPACE_LABELS[workspace]}>{content(workspace)}</ErrorBoundary>
        </div>
      ))}
    </ServerSignalsProvider>
  );
}

function App() {
  const session = useSession();
  const menu = useMenu();
  const [searchValue, setSearchValue] = useState("");
  const [activePanel, setActivePanel] = useState<OperationsTab>("cart");

  return (
    <div className="app-shell">
      <TopBar user={session.user} status={session.status} onLogout={session.logout} />
      <Workspaces
        key={session.user?.email ?? "guest"}
        session={session}
        menu={menu}
        searchValue={searchValue}
        onSearchChange={setSearchValue}
        activePanel={activePanel}
        onPanelChange={setActivePanel}
      />
    </div>
  );
}

export default App;
