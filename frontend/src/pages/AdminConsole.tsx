import { AdminPanel } from "../components/AdminPanel";
import { useAdminTools } from "../hooks/useAdminTools";

/** ROLE_ADMIN: manual order-status and dead-letter tools. */
export function AdminConsole() {
  const admin = useAdminTools();

  return (
    <main className="console">
      <section className="primary-workspace console__panel" aria-label="Admin console">
        <AdminPanel admin={admin} />
      </section>
    </main>
  );
}
