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

  const html = `<!doctype html>
<html lang="ru">
  <head>
    <meta charset="utf-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1" />
    <title>Добавить друга в ICU</title>
    <meta http-equiv="refresh" content="0;url=${appUrl}" />
    <style>
      body {
        margin: 0;
        min-height: 100vh;
        display: flex;
        align-items: center;
        justify-content: center;
        background: #fffbfe;
        color: #1d1b20;
        font-family: Arial, Helvetica, sans-serif;
      }
      main {
        width: min(100% - 40px, 460px);
        text-align: center;
      }
      h1 {
        margin: 0 0 12px;
        font-size: 28px;
        line-height: 1.2;
      }
      p {
        margin: 0 0 24px;
        color: #746c7d;
        font-size: 16px;
        line-height: 1.5;
      }
      a {
        display: block;
        border-radius: 14px;
        background: #6750a4;
        color: white;
        padding: 16px 20px;
        font-weight: 700;
        text-decoration: none;
      }
    </style>
  </head>
  <body>
    <main>
      <h1>Добавить друга в ICU</h1>
      <p>Если приложение не открылось автоматически, нажмите кнопку ниже.</p>
      <a href="${appUrl}">Открыть ICU</a>
    </main>
    <script>window.location.href = ${JSON.stringify(appUrl)};</script>
  </body>
</html>`;

  return new Response(html, {
    headers: {
      ...corsHeaders,
      "Content-Type": "text/html; charset=utf-8",
    },
  });
});
