import { useState } from "react";
import "./App.css";
import { ErrorBoundary } from "./components/ErrorBoundary";
import type { OperationsTab } from "./components/OperationsPanel";
import { TopBar } from "./components/TopBar";
import { WorkspaceSwitcher } from "./components/WorkspaceSwitcher";
import { useMenu } from "./hooks/useMenu";
import { useSession } from "./hooks/useSession";
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

function App() {
  const session = useSession();
  const menu = useMenu();
  const [searchValue, setSearchValue] = useState("");
  const [activePanel, setActivePanel] = useState<OperationsTab>("cart");
  const [choice, setChoice] = useState<{ userKey: string; workspace: Workspace } | null>(null);

  const { user } = session;
  const userKey = user?.email ?? "guest";
  const workspaces = availableWorkspaces(user);
  // A choice only holds for the user who made it; signing in, out, or expiring resets it.
  const workspace =
    choice && choice.userKey === userKey && workspaces.includes(choice.workspace)
      ? choice.workspace
      : defaultWorkspace(workspaces);

  return (
    <div className="app-shell">
      <TopBar user={user} status={session.status} onLogout={session.logout} />
      <WorkspaceSwitcher
        workspaces={workspaces}
        active={workspace}
        onChange={(next) => setChoice({ userKey, workspace: next })}
      />
      {/* Keyed by user and workspace: session-scoped state starts fresh on either change. */}
      <ErrorBoundary key={`${userKey}:${workspace}`} label={WORKSPACE_LABELS[workspace]}>
        {user && workspace === "merchant" ? <MerchantConsole user={user} /> : null}
        {user && workspace === "rider" ? <RiderConsole user={user} /> : null}
        {user && workspace === "admin" ? <AdminConsole menu={menu} /> : null}
        {workspace === "order" ? (
          <OrderingPage
            session={session}
            menu={menu}
            searchValue={searchValue}
            onSearchChange={setSearchValue}
            activePanel={activePanel}
            onPanelChange={setActivePanel}
          />
        ) : null}
      </ErrorBoundary>
    </div>
  );
}

export default App;
