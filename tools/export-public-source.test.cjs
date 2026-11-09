'use strict';
const test=require('node:test'), assert=require('node:assert/strict');
const fs=require('node:fs'), path=require('node:path'), os=require('node:os');
const {spawnSync}=require('node:child_process');
test('standalone source export has working privacy/docs links and can export itself',t=>{
  const root=fs.mkdtempSync(path.join(os.tmpdir(),'radio-source-export-'));
  assert.ok(path.resolve(root).startsWith(path.resolve(os.tmpdir())+path.sep));
  t.after(()=>fs.rmSync(root,{recursive:true,force:true}));
  const first=path.join(root,'first'), second=path.join(root,'second');
  function run(source,destination){
    const result=spawnSync(process.execPath,[path.join(source,'tools/export-public-source.cjs'),'--output',destination],{encoding:'utf8',windowsHide:true});
    assert.equal(result.status,0,result.stdout+result.stderr);
  }
  run(path.resolve(__dirname,'..'),first);
  assert.ok(fs.existsSync(path.join(first,'docs/privacy.html')),'exported privacy page must exist');
  for(const file of ['app/src/automotiveRelease/AndroidManifest.xml', 'docker/Dockerfile',
    'docker/entrypoint.sh', 'tools/docker-build.sh', 'docs/DOCKER-BUILD.md'])
    assert.ok(fs.existsSync(path.join(first,file)), `missing Docker/release input: ${file}`);
  const readme=fs.readFileSync(path.join(first,'README.md'),'utf8');
  const refs=[...readme.matchAll(/\[[^\]]*\]\(([^)]+)\)/g)].map(x=>x[1]);
  for(const ref of refs){
    if(/^(https?:|#)/.test(ref))continue;
    assert.ok(fs.existsSync(path.join(first,ref.split(/[?#]/)[0])),`missing README target: ${ref}`);
  }
  for (const sourceSet of ['phone', 'testPhone', 'testAutomotive']) {
    const fixture = path.join(first, 'app/src', sourceSet, 'java/fixture/ExportFixture.kt');
    fs.mkdirSync(path.dirname(fixture), { recursive: true });
    fs.writeFileSync(fixture, '// Source-set export fixture only.\n');
  }
  run(first,second);
  for (const sourceSet of ['phone', 'testPhone', 'testAutomotive'])
    assert.ok(fs.existsSync(path.join(second, 'app/src', sourceSet,
      'java/fixture/ExportFixture.kt')), `missing optional source set: ${sourceSet}`);
  assert.ok(fs.existsSync(path.join(second,'docs/privacy.html')));
});
