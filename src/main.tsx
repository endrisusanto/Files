import React from "react";
import { createRoot } from "react-dom/client";
import App from "./App";
import "./style.css";

createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>
);

// Show window only after first paint — eliminates blank black flash on startup
// Uses double-rAF to ensure the browser has committed the first visible frame
import("@tauri-apps/api/window").then(({ getCurrentWindow }) => {
  const show = () => {
    requestAnimationFrame(() => {
      requestAnimationFrame(() => {
        getCurrentWindow().show().catch(() => {});
      });
    });
  };
  if (document.readyState === "complete") {
    show();
  } else {
    window.addEventListener("load", show, { once: true });
  }
}).catch(() => {
  // Not running inside Tauri (e.g. browser dev), ignore
});
