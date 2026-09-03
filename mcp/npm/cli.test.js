'use strict';

const {spawn} = require('child_process');
const assert = require('node:assert/strict');
const {test} = require('node:test');
const fs = require('fs');
const os = require('os');
const path = require('path');
const {launch} = require('./cli.js');

const cli = path.join(__dirname, 'cli.js');

function runCli(env, args = ['--help']) {
  return new Promise((resolve, reject) => {
    const child = spawn(process.execPath, [cli, ...args], {
      env: {...process.env, ...env},
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    let stdout = '';
    let stderr = '';
    child.stdout.on('data', (chunk) => {
      stdout += chunk;
    });
    child.stderr.on('data', (chunk) => {
      stderr += chunk;
    });
    child.on('error', reject);
    child.on('close', (code) => resolve({code, stdout, stderr}));
  });
}

test('missing platform package fails on stderr with an npx example', async () => {
  const emptyModules = fs.mkdtempSync(path.join(os.tmpdir(), 'safedb-mcp-empty-'));
  const result = await runCli({NODE_PATH: emptyModules});
  assert.equal(result.code, 1);
  assert.equal(result.stdout, '');
  assert.match(result.stderr, /No bundled runtime/);
  assert.match(result.stderr, /npx -y @safe-db\/mcp/);
});

test('spawns bundled java with the jar, flags, and inherited stdio', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'safedb-mcp-cli-'));
  const pkgName = `@safe-db/mcp-${process.platform}-${process.arch}`;
  const pkgRoot = path.join(root, 'node_modules', ...pkgName.split('/'));
  const binDir = path.join(pkgRoot, 'jre', 'bin');
  const libDir = path.join(pkgRoot, 'lib');
  fs.mkdirSync(binDir, {recursive: true});
  fs.mkdirSync(libDir, {recursive: true});
  fs.writeFileSync(path.join(pkgRoot, 'package.json'), JSON.stringify({name: pkgName}));
  fs.writeFileSync(path.join(libDir, 'safe-db-mcp.jar'), 'jar');
  const javaName = process.platform === 'win32' ? 'java.exe' : 'java';
  const java = path.join(binDir, javaName);
  fs.writeFileSync(java, '');
  const jar = path.join(libDir, 'safe-db-mcp.jar');
  const child = {on() {}};
  let spawned;
  const returned = launch({
    spawn(command, args, options) {
      spawned = {command, args, options};
      return child;
    },
    modulePaths: [root],
    argv: ['connections', 'list'],
  });
  assert.equal(returned, child);
  assert.equal(spawned.command, java);
  assert.deepEqual(spawned.args, [
    '-Dfile.encoding=UTF-8',
    '--enable-native-access=ALL-UNNAMED',
    '-jar',
    jar,
    'connections',
    'list',
  ]);
  assert.deepEqual(spawned.options, {stdio: 'inherit'});
});
