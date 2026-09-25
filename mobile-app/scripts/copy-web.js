// Builds www/ for the Android app from the game (../index.html, the single source of truth).
//  - copies index.html
//  - swaps the Google Fonts link for the bundled copy of Outfit, so the app works offline
//    (LAN Versus on a phone hotspot has no internet)
//  - adds Capacitor's runtime (capacitor.js from @capacitor/core). Without it the page has no
//    Capacitor.registerPlugin, so it can't reach the native plugins (LAN Versus host + finding
//    games nearby, AdMob, the Back button) and behaves like the website.
// Run it through `npm run sync`, which then runs `npx cap sync android`.
const fs = require('fs');
const path = require('path');

const root = path.join(__dirname, '..');
const src = path.join(root, '..', 'index.html');
const www = path.join(root, 'www');

let html = fs.readFileSync(src, 'utf8');
const before = html;
html = html
  .replace(/<link rel="preconnect" href="https:\/\/fonts\.googleapis\.com">\s*/i, '')
  .replace(/<link href="https:\/\/fonts\.googleapis\.com\/css2\?family=Outfit[^"]*" rel="stylesheet">/i,
           '<link rel="stylesheet" href="fonts/outfit.css">');
if (html === before) console.warn('Note: the Google Fonts link was not found; the app will use the system font.');

const coreJs = path.join(root, 'node_modules', '@capacitor', 'core', 'dist', 'capacitor.js');
if (!fs.existsSync(coreJs)) {
  console.error('Missing ' + coreJs + '\nRun `npm install` in the mobile-app folder first.');
  process.exit(1);
}
const withCore = html.replace(/<script>/i, '<script src="capacitor.js"></script>\n<script>');
if (withCore === html) {
  console.error('Could not find the game <script> in index.html to load capacitor.js before it.');
  process.exit(1);
}
html = withCore;

fs.mkdirSync(path.join(www, 'fonts'), { recursive: true });
fs.writeFileSync(path.join(www, 'index.html'), html);
fs.copyFileSync(coreJs, path.join(www, 'capacitor.js'));
for (const f of fs.readdirSync(path.join(root, 'web-extra', 'fonts'))) {
  fs.copyFileSync(path.join(root, 'web-extra', 'fonts', f), path.join(www, 'fonts', f));
}
console.log('www/ updated from ../index.html');
