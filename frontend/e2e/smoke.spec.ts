import { expect, test, type Page } from "@playwright/test";

async function useIdentity(page: Page, user: string, roles: string[]) {
  await page.addInitScript(([u, r]) => localStorage.setItem("fixai.identity", JSON.stringify({ user: u, roles: r })), [user, roles] as const);
}

test("engineer runs a simulator smoke certification from the UI and sees the engine verdict", async ({ page }) => {
  await useIdentity(page, "ui.engineer", ["CERTIFICATION_ENGINEER"]);
  await page.goto("/");
  await expect(page.getByRole("heading", { name: "Overview" })).toBeVisible();

  await page.getByRole("link", { name: "New certification run" }).click();
  const form = page.getByRole("form", { name: "New certification run" });
  await expect(form.getByLabel("Suite", { exact: true })).toContainText("Smoke");
  await form.getByLabel("Suite", { exact: true }).selectOption("smoke");
  await form.getByLabel("Simulator profile").selectOption("COMPLIANT");
  await form.getByRole("button", { name: "Start run" }).click();

  await expect(page).toHaveURL(/\/runs\/[0-9a-f-]{36}$/);
  const verdict = page.locator("dt", { hasText: "Engine verdict" }).locator("xpath=following-sibling::dd");
  await expect(verdict).toHaveText(/passed/i, { timeout: 90_000 });
  await expect(page.getByRole("button", { name: /ORD-001/ })).toBeVisible();

  await page.getByRole("button", { name: "Replay-verify verdict" }).click();
  await expect(page.getByText(/reproduced every verdict; evidence digest matches/)).toBeVisible();

  await page.getByRole("button", { name: /ORD-001/ }).click();
  await expect(page.getByRole("heading", { name: "Evidence (redacted)" })).toBeVisible();
  await expect(page.locator(".evidence tbody tr").first()).toBeVisible();
});

test("knowledge answers carry verified citations", async ({ page }) => {
  await useIdentity(page, "ui.engineer", ["CERTIFICATION_ENGINEER"]);
  await page.goto("/knowledge");
  await page.getByLabel("Question").fill("How must a counterparty answer a TestRequest?");
  await page.getByRole("button", { name: "Ask", exact: true }).click();
  await expect(page.getByRole("heading", { name: "Answer" })).toBeVisible();
  await expect(page.locator("blockquote").first()).toContainText("TestReqID");
});

test("role-limited users do not get actions they cannot perform", async ({ page }) => {
  await useIdentity(page, "ui.auditor", ["AUDITOR"]);
  await page.goto("/runs");
  await expect(page.getByRole("heading", { name: "Certification runs" })).toBeVisible();
  await expect(page.getByRole("button", { name: "New run" })).toHaveCount(0);
  await page.goto("/audit");
  await page.getByRole("button", { name: "Verify chain" }).click();
  await expect(page.getByText(/Chain intact: \d+ events verified/)).toBeVisible();
});
