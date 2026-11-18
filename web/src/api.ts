import { accessToken } from "./auth";

/** The API's error format (RFC 9457 Problem Details), with field errors on 400 invalid-input. */
export type Problem = {
  type?: string;
  title?: string;
  status: number;
  detail?: string;
  errors?: { field: string; message: string }[];
};

export class ApiError extends Error {
  constructor(readonly problem: Problem) {
    super(problem.detail ?? problem.title ?? `HTTP ${problem.status}`);
  }
}

export async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers);
  headers.set("Authorization", `Bearer ${await accessToken()}`);
  if (init.body) {
    headers.set("Content-Type", "application/json");
  }
  const response = await fetch(path, { ...init, headers });
  if (!response.ok) {
    const problem: Problem = response.headers.get("Content-Type")?.includes("json")
      ? await response.json()
      : response.status >= 502
        ? { status: response.status, detail: "Dispatch isn't answering right now. This page retries when it's back." }
        : { status: response.status, title: response.statusText };
    throw new ApiError({ ...problem, status: response.status });
  }
  return response.status === 204 ? (undefined as T) : response.json();
}

export const post = <T>(path: string, body?: unknown) =>
  api<T>(path, { method: "POST", body: body === undefined ? undefined : JSON.stringify(body) });

// ---- API shapes (see the OpenAPI docs at /swagger-ui) ----

export type Status =
  | "DRAFT"
  | "PUBLISHED"
  | "ROUTED"
  | "ASSIGNED"
  | "IN_PROGRESS"
  | "SUBMITTED"
  | "APPROVED"
  | "PAID"
  | "CANCELLED";

export type Money = { minor: number; currency: string };
export type TimeWindow = { startsAt: string; endsAt: string };

export type WorkOrderSummary = {
  id: string;
  title: string;
  status: Status;
  siteAddress: string;
  timeWindow: TimeWindow;
  budget: Money;
  createdAt: string;
};

export type WorkOrder = WorkOrderSummary & {
  description: string;
  site: { latitude: number; longitude: number };
  requiredSkills: string[];
  technicianId: string | null;
  rejectionReason: string | null;
  completionNotes: string | null;
  checklist: { position: number; description: string }[];
};

export type Page<T> = { items: T[]; next: string | null };

/** GET /api/work-orders/{id}/audit: the AI check of the latest submission (step 3.5). */
export type Audit = {
  id: string;
  workOrderId: string;
  state: "NEEDS_REVIEW" | "AUTO_APPROVED" | "APPROVED" | "REJECTED" | "DECIDED_ELSEWHERE";
  model: string | null;
  promptVersion: string;
  items: { position: number; verdict: "MET" | "NOT_MET" | "UNCLEAR"; evidence: string }[];
  confidence: number | null;
  photoCount: number;
  recommendation: "APPROVE" | "REVIEW";
  reason: string;
  decisionReason: string | null;
};

export type Attachment = {
  id: string;
  fileName: string;
  contentType: string;
  size: number;
  uploadedAt: string;
  downloadUrl: string;
};

export type TechnicianProfile = {
  id: string;
  fullName: string;
  skills: string[];
  serviceRadiusKm: number;
  rating: number | null;
  status: string;
};

export type Me = { id: string; username: string; tenantId: string | null; roles: string[] };
export type Company = { id: string; name: string; slug: string };

/** What the live stream sends for each change (`work-order.status-changed`). */
export type StatusChange = {
  workOrderId: string;
  title: string | null;
  from: Status;
  to: Status;
  technicianId: string | null;
  occurredAt: string;
};

/** `GET /api/ai/usage`: a company's AI calls and their cost at list price, per UTC day and per feature. */
export type AiUsage = {
  from: string;
  to: string;
  today: { calls: number; limit: number };
  totals: AiUsageCounts;
  days: { date: string; calls: number; costUsd: number }[];
  features: (AiUsageCounts & { feature: string })[];
};

type AiUsageCounts = {
  calls: number;
  failed: number;
  skipped: number;
  inputTokens: number;
  outputTokens: number;
  costUsd: number;
};
