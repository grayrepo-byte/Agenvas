"use client";

import dynamic from "next/dynamic";

const App = dynamic(() => import("../App").then((module) => module.App), {
  ssr: false,
  loading: () => (
    <main className="min-h-screen bg-[var(--canvas)] p-8" role="status">
      正在加载页面…
    </main>
  ),
});

/** The existing SPA remains browser-only; Spring Boot is the sole business backend. */
export function ClientOnlyApplication() {
  return <App />;
}
