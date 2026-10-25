// Renders the "Glass page" launcher icon PNGs: node tools/render-icon.js res docs/icon.png
// Needs Playwright with Chromium.
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');
const res = process.argv[2];

const art = (id) => `
<defs><linearGradient id="${id}" x1="30" y1="0" x2="53" y2="0" gradientUnits="userSpaceOnUse">
<stop offset="0" stop-color="#5F86FF"/><stop offset="1" stop-color="#E9EEF7"/></linearGradient></defs>
<rect x="55" y="28" width="25" height="52" rx="3" fill="#E9EEF7"/>
<path d="M53 28 L53 80 L33.5 72.2 Q30 70.8 30 67 L30 41 Q30 37.2 33.5 35.8 Z" fill="url(#${id})"/>
<path d="M36 44 L47 40" stroke="#FFFFFF" stroke-opacity="0.6" stroke-width="2" stroke-linecap="round"/>`;
// Legacy icon: rounded square with the background baked in.
const full = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108"><rect width="108" height="108" rx="26" fill="#0E1116"/>${art('g')}</svg>`;
// Adaptive foreground: transparent 108x108 canvas; the art sits inside the 66-unit safe zone.
const fg = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108">${art('g')}</svg>`;

const densities = { mdpi: 1, hdpi: 1.5, xhdpi: 2, xxhdpi: 3, xxxhdpi: 4 };
(async () => {
  const browser = await chromium.launch();
  const page = await browser.newPage();
  async function shot(svg, size, out) {
    await page.setViewportSize({ width: size, height: size });
    await page.setContent(`<html><body style="margin:0;background:transparent">${svg.replace('<svg ', `<svg width="${size}" height="${size}" `)}</body></html>`);
    fs.mkdirSync(path.dirname(out), { recursive: true });
    await page.screenshot({ path: out, omitBackground: true, clip: { x: 0, y: 0, width: size, height: size } });
  }
  for (const [d, k] of Object.entries(densities)) {
    await shot(full, Math.round(48 * k), `${res}/mipmap-${d}/ic_launcher.png`);
    await shot(fg, Math.round(108 * k), `${res}/mipmap-${d}/ic_launcher_foreground.png`);
  }
  await shot(full, 512, process.argv[3]);
  await browser.close();
})();
