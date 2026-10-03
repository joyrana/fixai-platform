import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { App } from "./App";
import { IdentityProvider } from "./identity";
import "./styles.css";

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <IdentityProvider>
      <App />
    </IdentityProvider>
  </StrictMode>,
);
