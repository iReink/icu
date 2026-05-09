const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
};

Deno.serve((req) => {
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
  }

  const url = new URL(req.url);
  const token = url.searchParams.get("token") ?? "";
  const appUrl = `icu://friend-invite?token=${encodeURIComponent(token)}`;

  const headers = new Headers(corsHeaders);
  headers.set("location", appUrl);
  headers.set("cache-control", "no-store");

  return new Response("Opening ICU", {
    status: 302,
    headers,
  });
});
