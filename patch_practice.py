import re

with open('app/src/main/assets/practice_frontend.html', 'r', encoding='utf-8') as f:
    html = f.read()

# Replace CSS
old_css = r"\.option\{border:1px solid var\(--stroke\);border-radius:14px;padding:12px;display:flex;gap:10px;color:var\(--text2\)\}"
new_css = r".option{border:1px solid var(--stroke);border-radius:14px;padding:12px;display:flex;gap:10px;color:var(--text2);cursor:pointer;transition:all .2s ease}.option:active{transform:scale(0.98)}.option.correct{background:rgba(74,222,128,.1);border-color:rgba(74,222,128,.4)}.option.correct .olabel,.option.correct .content{color:var(--green)}.option.wrong{background:rgba(239,68,68,.1);border-color:rgba(239,68,68,.4)}.option.wrong .olabel,.option.wrong .content{color:var(--red)}"
html = re.sub(old_css, new_css, html)

# Replace JS function ro(o)
old_ro = r"function ro\(o\)\{var h='<div class=\"options\">';for\(var i=0;i<o\.length;i\+\+\)\{var v=o\[i\];var lb=v\.label\|\|String\.fromCharCode\(65\+i\);var bd=v\.content_md\|\|v\.content\|\|v\.text\|\|'';h\+='<div class=\"option\"><div class=\"olabel\">'\+esc\(lb\)\+'</div><div class=\"content\">'\+bd\+'</div></div>'\}return h\+'</div>'\}"

new_ro = r"""function ro(o){var h='<div class="options">';var q=cq();var cLabs=[];if(q&&q.answer&&q.answer.option_ids){for(var j=0;j<q.answer.option_ids.length;j++){for(var k=0;k<o.length;k++){if(o[k].id===q.answer.option_ids[j])cLabs.push(o[k].label||String.fromCharCode(65+k))}}}var cStr=cLabs.join(',');for(var i=0;i<o.length;i++){var v=o[i];var lb=v.label||String.fromCharCode(65+i);var bd=v.content_md||v.content||v.text||'';h+='<div class="option" data-label="'+esc(lb)+'" data-correct="'+esc(cStr)+'" onclick="checkOpt(this)"><div class="olabel">'+esc(lb)+'</div><div class="content">'+bd+'</div></div>'}return h+'</div>'}function checkOpt(el){var cStr=el.getAttribute('data-correct');if(!cStr)return;var cArr=cStr.split(',');var lb=el.getAttribute('data-label');var blk=el.parentElement;var opts=blk.querySelectorAll('.option');opts.forEach(function(o){o.classList.remove('wrong','correct')});if(cArr.indexOf(lb)>=0){el.classList.add('correct')}else{el.classList.add('wrong')}opts.forEach(function(o){if(cArr.indexOf(o.getAttribute('data-label'))>=0)o.classList.add('correct')})}"""

html = re.sub(old_ro, new_ro, html)

with open('app/src/main/assets/practice_frontend.html', 'w', encoding='utf-8') as f:
    f.write(html)
print("Patched practice_frontend.html")
