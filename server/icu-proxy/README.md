# ICU proxy

The public live-location page is served by the ICU VPS because hosted Supabase
projects without a custom domain intentionally rewrite HTML to plain text.
All tokens and location data are still validated and returned by the Supabase
`live-share` Edge Function through the restricted `/functions/v1/` proxy route.

Server paths:

- Nginx config: `/etc/nginx/sites-available/icu-proxy`
- Static page: `/var/www/icu-live-share`
- Restart script: `/root/icu-proxy/restart-icu-proxy.sh`
- Restart timer: `/etc/systemd/system/icu-proxy-restart.timer`
