#!/usr/bin/env bash
set -euo pipefail

nginx -t
systemctl reload nginx
curl --fail --silent --show-error https://icu-proxy.185-92-181-109.sslip.io/health
