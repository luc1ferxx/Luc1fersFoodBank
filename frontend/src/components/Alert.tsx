import type { ReactNode } from "react";

export type AlertTone = "neutral" | "success" | "danger";

export function Alert({ tone = "neutral", children }: { tone?: AlertTone; children?: ReactNode }) {
  if (!children) {
    return null;
  }

  return (
    <div className={`alert alert--${tone}`} role={tone === "danger" ? "alert" : "status"}>
      {children}
    </div>
  );
}
