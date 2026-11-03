#!/usr/bin/env node
'use strict';
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto');
const {spawnSync}=require('node:child_process');
const root=fs.realpathSync(path.resolve(__dirname,'..'));
const outputArg=process.argv.indexOf('--output');
if(outputArg>=0&&!process.argv[outputArg+1])throw new Error('--output requires a path');
const output=path.resolve(outputArg>=0?process.argv[outputArg+1]:path.join(root,'dist/public-source'));
const included=new Map(),warnings=[];
function fail(m){console.error('Export stopped: '+m);process.exit(1)}
function inside(base,p){const r=path.resolve(p);if(r!==base&&!r.startsWith(base+path.sep))fail('path escapes allowed root');return r}
function addFile(rel,dest=rel){const src=inside(root,path.join(root,rel)),st=fs.lstatSync(src);inside(root,fs.realpathSync(src));if(st.isSymbolicLink()||!st.isFile())fail('invalid allowed input: '+rel);if(dest.startsWith('/')||dest.split(/[\\/]/).includes('..'))fail('invalid destination');included.set(dest.replace(/\\/g,'/'),{source:src})}
function addTree(rel){const base=inside(root,path.join(root,rel));if(!fs.statSync(base).isDirectory())fail('missing allowed tree: '+rel);function walk(d){for(const e of fs.readdirSync(d,{withFileTypes:true}).sort((a,b)=>a.name.localeCompare(b.name))){const p=inside(root,path.join(d,e.name)),s=fs.lstatSync(p);if(s.isSymbolicLink())fail('symlink in allowed tree: '+path.relative(root,p));if(s.isDirectory())walk(p);else if(s.isFile())included.set(path.relative(root,p).replace(/\\/g,'/'),{source:p});else fail('unsupported file type in allowed tree')}}walk(base)}
for(const f of ['.gitignore','LICENSE','THIRD-PARTY-NOTICES.md','gradlew','gradlew.bat','gradle.properties','settings.gradle.kts','build.gradle.kts','app/build.gradle.kts','gradle/wrapper/gradle-wrapper.jar','gradle/wrapper/gradle-wrapper.properties','tools/aaos-fixture-server.cjs'])addFile(f);
for(const d of ['main','automotive','debug','test','androidTest'])addTree('app/src/'+d);
if(fs.existsSync(path.join(root,'app/src/automotiveDebug')))addTree('app/src/automotiveDebug');
for(const f of ['README.md','BUILD.md','SECURITY.md','CHANGELOG.md'])addFile('docs/public/'+f,f);
addFile('tools/export-public-source.cjs');
addFile('docs/DESIGN-SYSTEM.md');
addFile('.cursor/rules/streaming-radio-design.mdc');
for(const f of ['tools/check-dependencies.cjs','tools/check-dependencies.test.cjs','.github/workflows/android-ci.yml','.github/dependabot.yml'])addFile(f);
const gh=spawnSync('git',['-C',root,'rev-parse','HEAD'],{encoding:'utf8',windowsHide:true});
const gitHead=gh.status===0?gh.stdout.trim():null;
const gs=spawnSync('git',['-C',root,'status','--porcelain','--untracked-files=all'],{encoding:'utf8',windowsHide:true});
const workingTree=gs.status===0?(gs.stdout.trim()?'dirty':'clean'):'unknown';
if(!gitHead)warnings.push('Git HEAD unavailable; file hashes identify the exported contents.');
if(workingTree!=='clean')warnings.push('Working tree is '+workingTree+'; Git HEAD does not identify the exported file contents.');
let upstream=null;if(gitHead){const u=spawnSync('git',['-C',root,'show',gitHead+':README.md'],{encoding:'utf8',windowsHide:true,maxBuffer:4194304});if(u.status===0)upstream=u.stdout}
if(upstream===null){upstream='# Upstream README unavailable\n\nSee https://github.com/szczepl/twinsen-radio.\n';warnings.push('Could not retrieve upstream README from Git history; included a provenance note instead.')}
included.set('UPSTREAM-README.md',{generated:Buffer.from(upstream,'utf8')});
const exts=new Set(['.kt','.java','.xml','.json','.kts','.gradle','.properties','.cjs','.js','.md','.txt','.sh','.bat','.yml','.yaml','.toml','.pro']);
const pats=[['private key',/-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----/i],['GitHub token',/\bgh[pousr]_[A-Za-z0-9_]{30,}\b/],['AWS access key',/\bAKIA[0-9A-Z]{16}\b/],['Slack token',/\bxox[baprs]-[A-Za-z0-9-]{20,}\b/],['Google API key',/\bAIza[0-9A-Za-z_-]{30,}\b/],['OpenAI-style secret',/\bsk-(?:proj-)?[A-Za-z0-9_-]{24,}\b/],['authorization bearer',/\bBearer\s+[A-Za-z0-9._~+/-]{20,}/i],['URL user information',/https?:\/\/[^\s/:@]+:[^\s/@]+@/i],['credential assignment',/(?:password|passwd|secret|api[_-]?key|access[_-]?token)\s*[:=]\s*["'][^"']{12,}["']/i]];
const found=[];
function syntheticUserInfo(line,dest){
 if(!dest.startsWith('app/src/test/'))return false;
 const urls=line.match(/https?:\/\/[^\s"']+:[^\s"']+@[^\s"']+/g)||[];
 return urls.length>0&&urls.every(raw=>{try{const u=new URL(raw);const logFixture=dest==='app/src/test/java/net/mspanc/twinsenradio/playback/DiagnosticUrlsTest.kt'&&u.username==='listener'&&u.password==='example-password'&&u.hostname==='radio.example'&&u.pathname==='/signed-path'&&u.search==='?access_token=example-token'&&u.hash==='#example-fragment';return logFixture||(u.username==='user'&&['pass','secret'].includes(u.password)&&(u.hostname==='example.com'||u.hostname.endsWith('.example')))}catch{return false}});
}
for(const [dest,item] of included){
 const b=item.generated||fs.readFileSync(item.source);
 if(!exts.has(path.extname(dest).toLowerCase()))continue;
 const text=b.toString('utf8'),lines=text.split(/\r?\n/);
 for(let i=0;i<lines.length;i++)for(const [name,re] of pats){
  re.lastIndex=0;
  if(re.test(lines[i])){
   if(name==='URL user information'&&syntheticUserInfo(lines[i],dest))warnings.push('Reviewed reserved-domain rejection fixture in '+dest+':'+(i+1));
   else found.push({dest,line:i+1,name});
  }
 }
 if(/[A-Za-z]:[\\/]+Users[\\/]+elliott/i.test(text))found.push({dest,line:0,name:'personal local path'});
 if(/https?:\/\/(?:localhost|127\.0\.0\.1|10\.0\.2\.2|10\.0\.0\.\d+)(?=[:/\s"']|$)/i.test(text))warnings.push('Local or emulator endpoint appears in '+dest+'.');
 if(/eyyit\.com/i.test(text))warnings.push('Legacy personal-host reference remains in '+dest+'; review before GitHub publication.');
}
if(found.length){for(const f of found)console.error('Potential '+f.name+' at '+f.dest+':'+f.line+' (value suppressed).');fail('secret-pattern scan found possible credentials; inspect privately before export')}
warnings.push('Secret-pattern scan is limited and is not a comprehensive secret, privacy, license, or security audit.');
if(fs.existsSync(output)){const s=fs.lstatSync(output);if(s.isSymbolicLink()||!s.isDirectory())fail('destination is not a regular directory');if(fs.readdirSync(output).length)fail('destination is nonempty; move it aside manually before export')}else{let p=path.dirname(output);while(p!==path.dirname(p)&&!fs.existsSync(p))p=path.dirname(p);if(fs.existsSync(p)&&fs.realpathSync(p)!==p)fail('destination parent resolves through a symlink');fs.mkdirSync(output,{recursive:true})}
const real=fs.realpathSync(output);if(real!==output)fail('destination resolves through symlink');const records=[];
for(const [dest,item] of [...included].sort((a,b)=>a[0].localeCompare(b[0]))){const target=inside(real,path.join(real,dest));fs.mkdirSync(path.dirname(target),{recursive:true});const b=item.generated||fs.readFileSync(item.source);fs.writeFileSync(target,b,{flag:'wx'});records.push({path:dest,size:b.length,sha256:crypto.createHash('sha256').update(b).digest('hex')})}
const manifest={formatVersion:1,project:'Twinsen Radio independent source candidate',gitHead,workingTree,hashAlgorithm:'SHA-256',selfHashExcluded:true,manifestFilesExcludedFromIncludedFiles:['SOURCE-MANIFEST.json','SOURCE-MANIFEST.sha256'],includedFiles:records,warnings};const mb=Buffer.from(JSON.stringify(manifest,null,2)+'\n','utf8');fs.writeFileSync(path.join(real,'SOURCE-MANIFEST.json'),mb,{flag:'wx'});const mh=crypto.createHash('sha256').update(mb).digest('hex');fs.writeFileSync(path.join(real,'SOURCE-MANIFEST.sha256'),mh+'  SOURCE-MANIFEST.json\n',{flag:'wx'});console.log('Exported '+records.length+' allowlisted files. Manifest SHA-256: '+mh);for(const w of warnings)console.log('Warning: '+w);
