import type { Context } from "hono";
export interface Env {
  DB: D1Database;
  MEDIA: R2Bucket;
  ROOMS: DurableObjectNamespace;
  JOBS: Queue<Job>;
  APP_URL: string;
  LEGACY_APP_URL?: string;
  ENVIRONMENT: string;
  BETTER_AUTH_SECRET: string;
  BETA_INVITE_CODE: string;
  AI?: Ai;
  FCM_SERVICE_ACCOUNT?: string;
  SUMMARY_MODEL?: string;
}
export interface Job {
  id: string;
  kind: string;
  payload: Record<string, unknown>;
}
export type AppEnv = {
  Bindings: Env;
  Variables: { userId: string; requestId: string };
};
export type Ctx = Context<AppEnv>;
export interface Message {
  id: string;
  conversation_id: string;
  author_id: string;
  kind: string;
  state: string;
  created_at: number;
  text: string;
  urgent: number;
  ai_status: string;
}
export interface Upload {
  id: string;
  message_id: string;
  user_id: string;
  object_key: string;
  upload_id: string;
  mime: string;
  expected_bytes: number;
  state: string;
}
