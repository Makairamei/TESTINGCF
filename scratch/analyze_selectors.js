const fs = require('fs');
const path = require('path');

const root = 'D:/WEB/4/TESTINGCF';
const dirs = fs.readdirSync(root).filter(d => fs.statSync(path.join(root, d)).isDirectory() && !d.startsWith('.'));

let serverSelectorCount = 0;
let hardcodedCount = 0;
let otherCount = 0;
let details = [];

dirs.forEach(d => {
  if (['build', 'builds', 'gradle', 'scratch', 'scripts'].includes(d)) return;
  const ktFiles = [];
  function walk(p) {
    if (!fs.existsSync(p)) return;
    for (const f of fs.readdirSync(p)) {
      const full = path.join(p, f);
      if (fs.statSync(full).isDirectory()) walk(full);
      else if (f.endsWith('.kt')) ktFiles.push(full);
    }
  }
  walk(path.join(root, d));

  let usesServerSelector = false;
  let hasHardcodedSelector = false;

  ktFiles.forEach(f => {
    const text = fs.readFileSync(f, 'utf8');
    if (text.includes('getSelector') || text.includes('fetchSelectors') || text.includes('getSelectors') || text.includes('selectors[')) {
      usesServerSelector = true;
    }
    if (/select(?:First)?\s*\(\s*"[^"]+"\s*\)/.test(text)) {
      hasHardcodedSelector = true;
    }
  });

  if (usesServerSelector) {
    serverSelectorCount++;
    details.push({ plugin: d, type: 'SERVER_SIDE' });
  } else if (hasHardcodedSelector) {
    hardcodedCount++;
    details.push({ plugin: d, type: 'HARDCODED' });
  } else {
    otherCount++;
    details.push({ plugin: d, type: 'API_OR_OTHER' });
  }
});

console.log('--- ANALYSIS RESULT ---');
console.log('Total Plugins Checked:', details.length);
console.log('Server-side Selector Plugins:', serverSelectorCount);
console.log('Hardcoded Selector Plugins:', hardcodedCount);
console.log('API / Non-selector Plugins:', otherCount);
console.table(details);
