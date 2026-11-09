import {writeFileSync} from 'node:fs';
const paired = "root.child('claims').child($id).child('phoneUid').val() === auth.uid";
const owner = "data.child('ownerUid').val() === auth.uid";
const immutable = ['ownerUid', 'claimSecret', 'createdAt', 'expiresAt'].map(key =>
  `newData.child('${key}').val() === data.child('${key}').val()`).join(' && ');
const transfer = {
  '.read': `auth != null && data.child('expiresAt').val() > now && (${owner} || ${paired})`,
  '.write': `auth != null && $id.matches(/^[a-f0-9]{64}$/) && ((!newData.exists() && (!data.exists() || ${owner} || ${paired})) || (!data.exists() && !root.child('claims').child($id).exists() && newData.child('ownerUid').val() === auth.uid && newData.child('status').val() === 'waiting' && !newData.child('payload').exists() && newData.child('createdAt').val() <= now && newData.child('createdAt').val() >= now - 30000 && newData.child('expiresAt').val() > now) || (data.exists() && data.child('expiresAt').val() > now && ${paired} && ${immutable} && data.child('status').val() === 'waiting' && newData.child('status').val() === 'submitted'))`,
  '.validate': "newData.hasChildren(['ownerUid','claimSecret','createdAt','expiresAt','status']) && (newData.child('status').val() === 'waiting' || newData.child('payload').exists())",
  ownerUid: {'.validate': 'newData.isString() && newData.val().length > 0 && newData.val().length <= 128'},
  claimSecret: {'.validate': 'newData.isString() && newData.val().matches(/^[a-f0-9]{64}$/)'},
  createdAt: {'.validate': 'newData.isNumber()'},
  expiresAt: {'.validate': "newData.isNumber() && newData.val() > newData.parent().child('createdAt').val() && newData.val() <= newData.parent().child('createdAt').val() + 300000"},
  status: {'.validate': "newData.val() === 'waiting' || newData.val() === 'submitted'"},
  payload: {
    '.validate': "newData.hasChildren(['kind','value','name']) && newData.parent().child('status').val() === 'submitted'",
    kind: {'.validate': "newData.val() === 'url' || newData.val() === 'file'"},
    value: {'.validate': "newData.isString() && newData.val().length > 0 && ((newData.parent().child('kind').val() === 'url' && newData.val().length <= 4096 && newData.val().matches(/^https:\\/\\/[^\\/@?#]+([\\/?#].*)?$/)) || (newData.parent().child('kind').val() === 'file' && newData.val().length <= 1048576))"},
    name: {'.validate': 'newData.isString() && newData.val().length <= 512'},
    '$other': {'.validate': false}
  },
  '$other': {'.validate': false}
};
const linked = "root.child('transfers').child($id)";
const claim = {
  '.read': `auth != null && ${linked}.child('expiresAt').val() > now && (${linked}.child('ownerUid').val() === auth.uid || data.child('phoneUid').val() === auth.uid)`,
  '.write': `auth != null && $id.matches(/^[a-f0-9]{64}$/) && ((!newData.exists() && (!data.exists() || data.child('phoneUid').val() === auth.uid || ${linked}.child('ownerUid').val() === auth.uid)) || (newData.exists() && ${linked}.exists() && ${linked}.child('expiresAt').val() > now && ${linked}.child('status').val() === 'waiting' && newData.child('phoneUid').val() === auth.uid && newData.child('secret').val() === ${linked}.child('claimSecret').val() && (!data.exists() || (data.child('phoneUid').val() === auth.uid && newData.child('secret').val() === data.child('secret').val()))))`,
  '.validate': "newData.hasChildren(['phoneUid','secret'])",
  phoneUid: {'.validate': 'newData.isString() && newData.val().length > 0 && newData.val().length <= 128'},
  secret: {'.validate': 'newData.isString() && newData.val().matches(/^[a-f0-9]{64}$/)'},
  '$other': {'.validate': false}
};
writeFileSync(new URL('database.rules.json', import.meta.url), JSON.stringify({rules: {
  '.read': false, '.write': false, transfers: {'$id': transfer}, claims: {'$id': claim}
}}, null, 2) + '\n');
