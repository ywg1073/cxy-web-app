const fs = require('fs');

function fixHtml(path) {
    let content = fs.readFileSync(path, 'utf8');
    
    // Add drawer styles in the tablet media query
    const drawerPadCSS = `
.drawer-mask{align-items:center;justify-content:center;}
.drawer{max-width:440px;border-radius:24px;max-height:85vh;}
.drawer-handle{display:none;}
`;
    if (content.includes('@media (min-width:768px)')) {
        if (!content.includes('.drawer-mask{align-items:center')) {
            content = content.replace('@media (min-width:768px){', '@media (min-width:768px){\n' + drawerPadCSS);
        }
    }
    
    fs.writeFileSync(path, content, 'utf8');
}

fixHtml('app/src/main/assets/app_frontend.html');
fixHtml('app/src/main/assets/favorites_viewer.html');
