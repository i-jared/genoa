import { readEnv } from "./lib/env";

// Fail at server start, with a readable list of problems, rather than on the
// first request.
export function register() {
  readEnv();
}
