export function Alert({ tone = "neutral", children }) {
  if (!children) {
    return null;
  }

  return (
    <div className={`alert alert--${tone}`} role={tone === "danger" ? "alert" : "status"}>
      {children}
    </div>
  );
}
