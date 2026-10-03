import { BrowserRouter, Route, Routes } from "react-router-dom";
import { Layout } from "./components/Layout";
import { ApprovalsPage } from "./pages/Approvals";
import { AuditPage } from "./pages/Audit";
import { BrokersPage } from "./pages/Brokers";
import { KnowledgePage } from "./pages/Knowledge";
import { OverviewPage } from "./pages/Overview";
import { RunDetailPage } from "./pages/RunDetail";
import { RunsPage } from "./pages/Runs";
import { WorkflowDetailPage, WorkflowsPage } from "./pages/Workflows";

export function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route element={<Layout />}>
          <Route index element={<OverviewPage />} />
          <Route path="runs" element={<RunsPage />} />
          <Route path="runs/:runId" element={<RunDetailPage />} />
          <Route path="workflows" element={<WorkflowsPage />} />
          <Route path="workflows/:workflowId" element={<WorkflowDetailPage />} />
          <Route path="brokers" element={<BrokersPage />} />
          <Route path="approvals" element={<ApprovalsPage />} />
          <Route path="knowledge" element={<KnowledgePage />} />
          <Route path="audit" element={<AuditPage />} />
          <Route path="*" element={<p>Page not found.</p>} />
        </Route>
      </Routes>
    </BrowserRouter>
  );
}
