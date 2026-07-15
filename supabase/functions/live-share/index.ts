import {
  APP_JS_BASE64,
  INDEX_HTML_BASE64,
  LEAFLET_CSS_BASE64,
  LEAFLET_JS_BASE64,
} from "./web_assets.ts";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "content-type",
  "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
};

const noStoreHeaders = {
  ...corsHeaders,
  "Cache-Control": "no-store",
};

const assetVersion = "1.2.0";
const bucket = "live-share-web";
const indexObject = `index-${assetVersion}.html`;
let assetsReady = false;

type ShareRow = {
  id: string;
  owner_id: string;
  created_at: string;
  expires_at: string | null;
  revoked_at: string | null;
};

type PointRow = {
  latitude: number;
  longitude: number;
  recorded_at: string;
};

function secretKey(): string {
  const legacy = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  if (legacy) return legacy;
  const raw = Deno.env.get("SUPABASE_SECRET_KEYS") ?? "{}";
  const keys = JSON.parse(raw) as Record<string, string>;
  const key = keys.default ?? Object.values(keys)[0];
  if (!key) throw new Error("Supabase secret key is unavailable");
  return key;
}

function supabaseUrl(): string {
  const value = Deno.env.get("SUPABASE_URL");
  if (!value) throw new Error("SUPABASE_URL is unavailable");
  return value.replace(/\/$/, "");
}

function adminHeaders(extra: Record<string, string> = {}): HeadersInit {
  const key = secretKey();
  return {
    apikey: key,
    Authorization: `Bearer ${key}`,
    ...extra,
  };
}

function decodeBase64(value: string): Uint8Array {
  const binary = atob(value);
  return Uint8Array.from(binary, (character) => character.charCodeAt(0));
}

async function uploadAsset(name: string, type: string, base64: string): Promise<void> {
  const response = await fetch(`${supabaseUrl()}/storage/v1/object/${bucket}/${name}`, {
    method: "POST",
    headers: adminHeaders({
      "Content-Type": type,
      "x-upsert": "true",
      "Cache-Control": name.endsWith(".html") ? "no-cache" : "public, max-age=31536000, immutable",
    }),
    body: decodeBase64(base64).buffer as ArrayBuffer,
  });
  if (!response.ok) {
    throw new Error(`Could not upload ${name}: ${response.status} ${await response.text()}`);
  }
}

async function assetExists(name: string): Promise<boolean> {
  const response = await fetch(`${supabaseUrl()}/storage/v1/object/${bucket}/${name}`, {
    method: "HEAD",
    headers: adminHeaders(),
  });
  if (response.ok) return true;
  if (response.status === 400 || response.status === 404) return false;
  throw new Error(`Could not inspect ${name}: ${response.status} ${await response.text()}`);
}

async function ensureWebAssets(): Promise<void> {
  if (assetsReady) return;
  const assets = [
    [indexObject, "text/html", INDEX_HTML_BASE64],
    [`app-${assetVersion}.js`, "application/javascript", APP_JS_BASE64],
    ["leaflet-1.9.4.js", "application/javascript", LEAFLET_JS_BASE64],
    ["leaflet-1.9.4.css", "text/css", LEAFLET_CSS_BASE64],
  ] as const;
  await Promise.all(assets.map(async ([name, type, base64]) => {
    if (!await assetExists(name)) await uploadAsset(name, type, base64);
  }));
  assetsReady = true;
}

async function sha256Hex(value: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value));
  return Array.from(new Uint8Array(digest))
    .map((byte) => byte.toString(16).padStart(2, "0"))
    .join("");
}

async function restJson<T>(path: string): Promise<T> {
  const response = await fetch(`${supabaseUrl()}/rest/v1/${path}`, {
    headers: adminHeaders({ Accept: "application/json" }),
  });
  if (!response.ok) {
    throw new Error(`Supabase REST failed: ${response.status} ${await response.text()}`);
  }
  return await response.json() as T;
}

async function findShare(token: string): Promise<ShareRow | null> {
  const hash = await sha256Hex(token);
  const rows = await restJson<ShareRow[]>(
    `public_location_shares?select=id,owner_id,created_at,expires_at,revoked_at&token_hash=eq.${hash}&limit=1`,
  );
  return rows[0] ?? null;
}

async function fetchProfileName(ownerId: string): Promise<string> {
  const rows = await restJson<Array<{ first_name: string; last_name: string }>>(
    `user_profiles?select=first_name,last_name&user_id=eq.${encodeURIComponent(ownerId)}&limit=1`,
  );
  const profile = rows[0];
  if (!profile) return "Пользователь ICU";
  return [profile.first_name, profile.last_name].filter((part) => part?.trim()).join(" ") || "Пользователь ICU";
}

async function fetchPoints(
  ownerId: string,
  shareCreatedAt: string,
  cursor: string | null,
): Promise<PointRow[]> {
  const dayAgo = Date.now() - 24 * 60 * 60 * 1000;
  const historyStart = new Date(Math.max(dayAgo, new Date(shareCreatedAt).getTime())).toISOString();
  const lowerBound = cursor && new Date(cursor).getTime() >= new Date(historyStart).getTime()
    ? cursor
    : historyStart;
  const operator = cursor ? "gt" : "gte";
  const points: PointRow[] = [];
  const pageSize = 1000;

  for (let offset = 0; offset < 10_000; offset += pageSize) {
    const page = await restJson<PointRow[]>(
      `location_points?select=latitude,longitude,recorded_at` +
        `&user_id=eq.${encodeURIComponent(ownerId)}` +
        `&recorded_at=${operator}.${encodeURIComponent(lowerBound)}` +
        `&order=recorded_at.asc&limit=${pageSize}&offset=${offset}`,
    );
    points.push(...page);
    if (page.length < pageSize) break;
  }

  if (points.length === 0 && !cursor) {
    const latest = await restJson<PointRow[]>(
      `location_points?select=latitude,longitude,recorded_at` +
        `&user_id=eq.${encodeURIComponent(ownerId)}` +
        `&recorded_at=gte.${encodeURIComponent(shareCreatedAt)}` +
        `&order=recorded_at.desc&limit=1`,
    );
    return latest.reverse();
  }
  return points;
}

function compactPoints(points: PointRow[]): PointRow[] {
  const result: PointRow[] = [];
  for (const point of points) {
    const previous = result[result.length - 1];
    if (previous && previous.latitude === point.latitude && previous.longitude === point.longitude) {
      result[result.length - 1] = point;
    } else {
      result.push(point);
    }
  }
  return result;
}

function json(body: unknown, status = 200): Response {
  return Response.json(body, { status, headers: noStoreHeaders });
}

async function handleData(req: Request): Promise<Response> {
  const body = await req.json().catch(() => null) as { token?: string; cursor?: string | null } | null;
  const token = body?.token?.trim() ?? "";
  if (!/^[A-Za-z0-9_-]{40,128}$/.test(token)) return json({ error: "Invalid link" }, 404);

  const share = await findShare(token);
  if (!share) return json({ error: "Share not found" }, 404);
  const now = Date.now();
  if (share.revoked_at || (share.expires_at && new Date(share.expires_at).getTime() <= now)) {
    return json({ error: "Share ended" }, 410);
  }

  const requestedCursor = body?.cursor && Number.isFinite(new Date(body.cursor).getTime())
    ? new Date(body.cursor).toISOString()
    : null;
  const [displayName, rawPoints] = await Promise.all([
    fetchProfileName(share.owner_id),
    fetchPoints(share.owner_id, share.created_at, requestedCursor),
  ]);
  const points = compactPoints(rawPoints);
  const cursor = rawPoints[rawPoints.length - 1]?.recorded_at ?? requestedCursor;
  return json({
    displayName,
    createdAt: share.created_at,
    expiresAt: share.expires_at,
    cursor,
    points: points.map((point) => ({
      latitude: point.latitude,
      longitude: point.longitude,
      recordedAt: point.recorded_at,
    })),
  });
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });
  try {
    if (req.method === "POST") return await handleData(req);
    if (req.method !== "GET") return json({ error: "Method not allowed" }, 405);

    await ensureWebAssets();
    const location = `${supabaseUrl()}/storage/v1/object/public/${bucket}/${indexObject}`;
    return new Response(null, {
      status: 302,
      headers: { ...noStoreHeaders, Location: location },
    });
  } catch (error) {
    console.error(error instanceof Error ? error.message : error);
    return json({ error: "Service unavailable" }, 503);
  }
});
