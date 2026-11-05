#!/bin/sh
# 编码/日志/内存/临时文件检查
node -e "
const j=JSON.parse(require('fs').readFileSync('/tmp/v1.json','utf8'));
const t=j.items[0].title;
console.log('title contains keyword:', t.includes('番茄炒蛋'), '| title len:', t.length);
console.log('title escaped:', JSON.stringify(t).slice(0,70));
console.log('author escaped:', JSON.stringify(j.items[0].author));
console.log('items count:', j.items.length);
console.log('残留em标签条数:', j.items.filter(x=>x.title.includes('em class')).length);
"
echo ---JOURNAL---
journalctl -u shiguang-backend --since '-15 min' --no-pager | grep -iE 'videos|error|uncaught|unhandled' | head -10
echo '(若上方无输出=无错误日志)'
echo ---MEM---
systemctl status shiguang-backend --no-pager | grep Memory
echo ---ASTRBOT---
systemctl is-active astrbot
echo ---TMPFILES---
ls /tmp/ | grep -E '^(v_|v1|verify_videos|test_bili)' 
echo DONE
