import { afterEach, describe, expect, it, vi } from "vitest";
import { api, ApiError, setRequestIdentity } from "./api";
import { mockFetch } from "./test/mockFetch";

afterEach(() => vi.unstubAllGlobals());

describe("api client", () => {
  it("sends the dev identity and an idempotency key when starting a run", async () => {
    const calls = mockFetch([["POST", /certification-runs$/, () => ({ status: 202, body: { id: "r1" } })]]);
    setRequestIdentity({ user: "alice", roles: ["CERTIFICATION_ENGINEER", "REVIEWER"] });
    await api.startRun({ fixVersion: "FIX44", suiteId: "smoke", target: { type: "SIMULATOR", simulatorProfile: "COMPLIANT" } });
    const call = calls[0]!;
    expect(call.url).toBe("/api/cert/api/v1/certification-runs");
    expect(call.headers.get("X-Dev-User")).toBe("alice");
    expect(call.headers.get("X-Dev-Roles")).toBe("CERTIFICATION_ENGINEER,REVIEWER");
    expect(call.headers.get("Idempotency-Key")).toMatch(/^ui-[0-9a-f-]{36}$/);
    expect(call.body).toEqual({ fixVersion: "FIX44", suiteId: "smoke", target: { type: "SIMULATOR", simulatorProfile: "COMPLIANT" } });
  });

  it("turns problem details into ApiError with codes", async () => {
    mockFetch([["POST", /decision$/, () => ({ status: 409, body: { title: "Conflict", detail: "Requesters cannot decide their own requests", codes: ["FOUR_EYES"] } })]]);
    const error = await api.decide("a1", "APPROVE", "looks right to me").catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({ status: 409, detail: "Requesters cannot decide their own requests", codes: ["FOUR_EYES"] });
  });
});
