package com.fixai.platform.certification.application.service;

import java.util.Map;

/** Renders a {@link CertificationReport} as a self-contained, print-friendly HTML document. All values are escaped. */
public final class ReportHtmlRenderer {

    public String render(CertificationReport report) {
        CertificationReport.RunSection run = report.run();
        StringBuilder html = new StringBuilder(16_384);
        html.append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
                .append("<title>FIX Certification Report ").append(e(report.runId().toString())).append("</title><style>")
                .append("body{font:14px/1.5 system-ui,sans-serif;margin:24px;color:#1b1f24;background:#fff}")
                .append("h1{font-size:22px;margin:0 0 4px}h2{font-size:17px;margin:28px 0 8px}")
                .append("table{border-collapse:collapse;width:100%;margin:8px 0}th,td{border:1px solid #d0d7de;padding:6px 8px;text-align:left;vertical-align:top}")
                .append("th{background:#f6f8fa}.PASSED{color:#1a7f37;font-weight:600}.FAILED{color:#cf222e;font-weight:600}")
                .append(".ERROR,.INCONCLUSIVE,.CANCELLED{color:#9a6700;font-weight:600}code{font:12px ui-monospace,monospace;word-break:break-all}")
                .append(".meta td:first-child{width:220px;color:#57606a}.note{color:#57606a;font-size:12px}")
                .append("</style></head><body>");
        html.append("<h1>FIX Certification Report</h1><div class=\"note\">Run ").append(e(report.runId().toString()))
                .append(" &middot; generated ").append(e(String.valueOf(report.generatedAt()))).append("</div>");
        html.append("<h2>Verdict: <span class=\"").append(e(String.valueOf(run.verdict()))).append("\">")
                .append(e(String.valueOf(run.verdict()))).append("</span></h2>");
        html.append("<table class=\"meta\">");
        row(html, "Status", run.status());
        row(html, "FIX version", run.fixVersion());
        row(html, "Target", run.targetType() + " / " + run.environment()
                + (run.simulatorProfile() == null ? "" : " / profile " + run.simulatorProfile()));
        row(html, "Target CompID", run.targetCompId());
        row(html, "Suite", run.suiteId());
        row(html, "Requested by", run.requestedBy());
        row(html, "Correlation ID", run.correlationId());
        row(html, "Started / completed", run.startedAt() + " / " + run.completedAt());
        row(html, "Duration (ms)", String.valueOf(run.durationMillis()));
        row(html, "Engine version", report.engineVersion());
        row(html, "Scenario catalogue SHA-256", report.catalogueHash());
        row(html, "Evidence digest SHA-256", report.evidenceDigest());
        html.append("</table>");

        CertificationReport.Summary summary = report.summary();
        html.append("<h2>Summary</h2><table><tr><th>Category</th><th>Total</th><th>Passed</th><th>Failed</th><th>Error</th></tr>");
        for (Map.Entry<String, CertificationReport.CategoryCounts> entry : summary.byCategory().entrySet()) {
            CertificationReport.CategoryCounts c = entry.getValue();
            html.append("<tr><td>").append(e(entry.getKey())).append("</td><td>").append(c.total()).append("</td><td>")
                    .append(c.passed()).append("</td><td>").append(c.failed()).append("</td><td>").append(c.errored()).append("</td></tr>");
        }
        html.append("<tr><th>All</th><th>").append(summary.total()).append("</th><th>").append(summary.passed())
                .append("</th><th>").append(summary.failed()).append("</th><th>").append(summary.errored()).append("</th></tr></table>");

        html.append("<h2>Scenarios</h2><table><tr><th>Scenario</th><th>Category</th><th>Status</th><th>Details</th></tr>");
        for (CertificationReport.ScenarioSection s : report.scenarios()) {
            html.append("<tr><td><code>").append(e(s.scenarioId() + "@" + s.scenarioVersion())).append("</code><br>")
                    .append(e(s.title())).append(s.mandatory() ? "" : " <span class=\"note\">(optional)</span>")
                    .append("</td><td>").append(e(s.category())).append("</td><td class=\"").append(e(s.status())).append("\">")
                    .append(e(s.status())).append("</td><td>");
            if (s.failureSummary() != null) {
                html.append(e(s.failureSummary()));
            }
            if (!s.failedAssertions().isEmpty() || !s.failedProtocolChecks().isEmpty()) {
                html.append("<table><tr><th>Step</th><th>Check</th><th>Expected</th><th>Actual</th><th>Evidence</th></tr>");
                for (CertificationReport.FailedAssertion a : s.failedAssertions()) {
                    assertionRow(html, a);
                }
                for (CertificationReport.FailedAssertion a : s.failedProtocolChecks()) {
                    assertionRow(html, a);
                }
                html.append("</table>");
            }
            html.append("<div class=\"note\">").append(s.evidenceCount()).append(" evidence records &middot; ")
                    .append(s.durationMillis()).append(" ms</div></td></tr>");
        }
        html.append("</table><p class=\"note\">").append(e(report.disclaimer())).append("</p></body></html>");
        return html.toString();
    }

    private static void assertionRow(StringBuilder html, CertificationReport.FailedAssertion a) {
        html.append("<tr><td>").append(a.step() == null ? "protocol" : a.step().toString()).append("</td><td>")
                .append(e(a.subject())).append("</td><td><code>").append(e(a.expected())).append("</code></td><td><code>")
                .append(e(a.actual())).append("</code></td><td>")
                .append(a.evidenceOrdinal() == null ? "" : "#" + a.evidenceOrdinal()).append("</td></tr>");
    }

    private static void row(StringBuilder html, String label, String value) {
        html.append("<tr><td>").append(e(label)).append("</td><td><code>").append(e(String.valueOf(value))).append("</code></td></tr>");
    }

    static String e(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length());
        for (char c : value.toCharArray()) {
            switch (c) {
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '&' -> out.append("&amp;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}
