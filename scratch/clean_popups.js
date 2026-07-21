const fs = require('fs');
const path = require('path');

const root = 'D:/WEB/4/TESTINGCF';

function cleanDirectory(dir) {
  const files = fs.readdirSync(dir);
  files.forEach(file => {
    const fullPath = path.join(dir, file);
    if (fs.statSync(fullPath).isDirectory()) {
      cleanDirectory(fullPath);
    } else if (file === 'StarPopupHelper.kt') {
      console.log('Deleting:', fullPath);
      fs.unlinkSync(fullPath);
    } else if (file.endsWith('.kt')) {
      let content = fs.readFileSync(fullPath, 'utf8');
      if (content.includes('StarPopupHelper')) {
        let lines = content.split('\n');
        lines = lines.filter(line => !line.includes('StarPopupHelper.showStarPopupIfNeeded') && !line.includes('import ') || !line.includes('StarPopupHelper'));
        let newContent = lines.join('\n');
        // Clean any standalone StarPopupHelper calls that spanned lines or remaining regex
        newContent = newContent.replace(/context\?\s*\.\s*let\s*\{\s*StarPopupHelper\.showStarPopupIfNeeded\([^)]*\)\s*\}/g, '');
        newContent = newContent.replace(/StarPopupHelper\.showStarPopupIfNeeded\([^)]*\);?/g, '');
        fs.writeFileSync(fullPath, newContent, 'utf8');
        console.log('Cleaned StarPopupHelper references in:', fullPath);
      }
    }
  });
}

cleanDirectory(root);
console.log('Done cleaning StarPopupHelper!');
