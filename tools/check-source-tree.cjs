// Catch stale upstream app sources left behind by overlaying a prepared export.
'use strict';
const fs=require('node:fs'),path=require('node:path'),{execFileSync}=require('node:child_process');
const root=path.resolve(__dirname,'..');
const manifest=JSON.parse(fs.readFileSync(path.join(root,'SOURCE-MANIFEST.json'),'utf8'));
const expected=new Set(manifest.includedFiles.map(x=>x.path).filter(x=>x.startsWith('app/src/')));
const actual=new Set(execFileSync('git',['-C',root,'ls-files','-z','--','app/src'],{encoding:'utf8'}).split('\0').filter(Boolean));
const extra=[...actual].filter(x=>!expected.has(x));
const missing=[...expected].filter(x=>!actual.has(x));
if(extra.length||missing.length){
 console.error('App source tree differs from the prepared source inventory.');
 for(const name of extra)console.error('Unexpected upstream/source file: '+name);
 for(const name of missing)console.error('Missing prepared source file: '+name);
 process.exitCode=1;
}else console.log('Prepared app source tree verified: '+actual.size+' files.');
