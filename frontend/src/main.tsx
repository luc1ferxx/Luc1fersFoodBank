import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import "./index.css";
import App from "./App";
import { ErrorBoundary } from "./components/ErrorBoundary";

const container = document.getElementById("root");

if (!container) {
  throw new Error("index.html is missing the #root element.");
}

createRoot(container).render(
  <StrictMode>
    <ErrorBoundary label="Luc1fer's Food Bank">
      <App />
    </ErrorBoundary>
  </StrictMode>,
);
