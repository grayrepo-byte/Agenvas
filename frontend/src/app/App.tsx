import { QueryClientProvider } from "@tanstack/react-query";
import { lazy, Suspense, useState } from "react";
import { BrowserRouter, Navigate, Route, Routes } from "react-router";
import { createQueryClient } from "./queryClient";
import { LoadingState } from "../shared/ui/LoadingState";
import "../shared/ui/PageTheme.css";

/** Each route loads only its own screen; React Flow stays out of auth and list bundles. */
const SetupPage = lazy(() => import("../features/auth/SetupPage")
  .then((module) => ({ default: module.SetupPage })));
const LoginPage = lazy(() => import("../features/auth/LoginPage")
  .then((module) => ({ default: module.LoginPage })));
const ProjectsPage = lazy(() => import("../features/projects/ProjectsPage")
  .then((module) => ({ default: module.ProjectsPage })));
const ProjectWorkspacePage = lazy(() => import("../features/canvas/ProjectWorkspacePage")
  .then((module) => ({ default: module.ProjectWorkspacePage })));
const LlmSettingsPage = lazy(() => import("../features/settings/LlmSettingsPage")
  .then((module) => ({ default: module.LlmSettingsPage })));
const MediaSettingsPage = lazy(() => import("../features/settings/MediaSettingsPage")
  .then((module) => ({ default: module.MediaSettingsPage })));
const SystemDiagnosticsPage = lazy(() => import("../features/settings/SystemDiagnosticsPage")
  .then((module) => ({ default: module.SystemDiagnosticsPage })));

export function App() {
  const [queryClient] = useState(createQueryClient);

  return (
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <Suspense fallback={<main className="app-page flex items-center justify-center"><LoadingState label="正在加载页面" /></main>}>
          <Routes>
          <Route path="/setup" element={<SetupPage />} />
          <Route path="/login" element={<LoginPage />} />
          <Route path="/projects" element={<ProjectsPage />} />
          <Route path="/settings/llm" element={<LlmSettingsPage />} />
          <Route path="/settings/providers" element={<LlmSettingsPage />} />
          <Route path="/settings/media" element={<MediaSettingsPage />} />
          <Route path="/settings/general" element={<SystemDiagnosticsPage />} />
          <Route path="/projects/:projectId" element={<ProjectWorkspacePage />} />
          <Route path="/" element={<Navigate to="/setup" replace />} />
          <Route path="*" element={<Navigate to="/setup" replace />} />
          </Routes>
        </Suspense>
      </BrowserRouter>
    </QueryClientProvider>
  );
}
