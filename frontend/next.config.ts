import type { NextConfig } from "next";
import { PHASE_DEVELOPMENT_SERVER } from "next/constants";

const BACKEND_DEVELOPMENT_ORIGIN = "http://localhost:8080";

/**
 * Production is a static export served by Nginx. The development-only rewrite
 * preserves the former same-origin Vite workflow without introducing a Next.js
 * API, Server Action, or production Node runtime.
 */
export default function nextConfig(phase: string): NextConfig {
  const config: NextConfig = {
    agentRules: false,
    reactStrictMode: true,
  };

  if (phase === PHASE_DEVELOPMENT_SERVER) {
    config.rewrites = async () => [{
      source: "/api/:path*",
      destination: `${BACKEND_DEVELOPMENT_ORIGIN}/api/:path*`,
    }];
  } else {
    config.output = "export";
  }

  return config;
}
