// Older beta builds use this origin. Forward through the same authenticated API
// so existing sessions, media requests, and live subscriptions keep working.
export default {
  fetch(request: Request, env: { GENOA_API: Fetcher }): Promise<Response> {
    const url = new URL(request.url);
    url.hostname = "genoa-api.hp-accounts.workers.dev";
    url.protocol = "https:";
    url.port = "";
    return env.GENOA_API.fetch(new Request(url, request));
  },
};
