import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const directory = path.dirname(fileURLToPath(import.meta.url));
const webDirectory = path.join(directory, "web");
const assets = [
  ["INDEX_HTML_BASE64", "index.html"],
  ["APP_JS_BASE64", "app.js"],
  ["LEAFLET_JS_BASE64", "leaflet-1.9.4.js"],
  ["LEAFLET_CSS_BASE64", "leaflet-1.9.4.css"],
];
const output = ["// Generated from web/*; keep source assets readable and edit those first."];

for (const [constantName, fileName] of assets) {
  const value = fs.readFileSync(path.join(webDirectory, fileName)).toString("base64");
  output.push(`export const ${constantName} = "${value}";`);
}

fs.writeFileSync(path.join(directory, "web_assets.ts"), `${output.join("\n")}\n`);
