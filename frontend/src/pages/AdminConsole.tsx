import { useState } from "react";
import { MerchantAccounts, RiderAccounts } from "../components/AccountsPanels";
import { AdminPanel } from "../components/AdminPanel";
import { AfterSalesAdminPanel } from "../components/AfterSalesAdminPanel";
import { ErrorBoundary } from "../components/ErrorBoundary";
import { useAdminTools } from "../hooks/useAdminTools";
import { useAfterSalesAdmin } from "../hooks/useAfterSalesAdmin";
import type { MenuBrowser } from "../hooks/useMenu";

type AdminSection = "merchants" | "riders" | "orders" | "after-sales";

const SECTIONS: readonly [AdminSection, string][] = [
  ["merchants", "Merchants"],
  ["riders", "Riders"],
  ["orders", "Order tools"],
  ["after-sales", "After-sales"],
];

/**
 * ROLE_ADMIN: merchant and rider provisioning, the manual order and DLT tools, and
 * after-sales arbitration and refund retries. The hooks that hold Idempotency-Keys
 * live here, above the sections, so a failed command keeps its key while another
 * section or workspace is shown.
 */
export function AdminConsole({ menu }: { menu: MenuBrowser }) {
  const [section, setSection] = useState<AdminSection>("merchants");
  const admin = useAdminTools();
  const afterSales = useAfterSalesAdmin();

  return (
    <main className="console">
      <section className="primary-workspace console__panel" aria-label="Admin console">
        <div className="panel-tabs admin-tabs" role="group" aria-label="Admin sections">
          {SECTIONS.map(([key, label]) => (
            <button
              key={key}
              className={section === key ? "active" : ""}
              type="button"
              aria-pressed={section === key}
              onClick={() => setSection(key)}
            >
              {label}
            </button>
          ))}
        </div>
        <ErrorBoundary key={section} label="This section">
          {section === "merchants" ? (
            <MerchantAccounts
              restaurants={menu.restaurants}
              restaurantsLoading={menu.restaurantsLoading}
              restaurantsError={menu.restaurantsError}
            />
          ) : null}
          {section === "riders" ? <RiderAccounts /> : null}
          {section === "orders" ? <AdminPanel admin={admin} /> : null}
          {section === "after-sales" ? <AfterSalesAdminPanel admin={afterSales} /> : null}
        </ErrorBoundary>
      </section>
    </main>
  );
}
