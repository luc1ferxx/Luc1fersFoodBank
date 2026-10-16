import { useState } from "react";
import "./App.css";
import { ErrorBoundary } from "./components/ErrorBoundary";
import { TopBar } from "./components/TopBar";
import { useMenu } from "./hooks/useMenu";
import { useSession } from "./hooks/useSession";
import { OrderingPage } from "./pages/OrderingPage";

function App() {
  const session = useSession();
  const menu = useMenu();
  const [searchValue, setSearchValue] = useState("");
  const [activePanel, setActivePanel] = useState("cart");

  return (
    <div className="app-shell">
      <TopBar user={session.user} status={session.status} onLogout={session.logout} />
      <ErrorBoundary key={session.user?.email ?? "guest"} label="The ordering workspace">
        <OrderingPage
          session={session}
          menu={menu}
          searchValue={searchValue}
          onSearchChange={setSearchValue}
          activePanel={activePanel}
          onPanelChange={setActivePanel}
        />
      </ErrorBoundary>
    </div>
  );
}

export default App;
