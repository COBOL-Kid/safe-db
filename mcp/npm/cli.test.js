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

function setupFakePlatformPkg({withJava = true, withJar = true, javaContent = '', javaMode} = {}) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'safedb-mcp-cli-'));
  const pkgName = `@safe-db/mcp-${process.platform}-${process.arch}`;
  const pkgRoot = path.join(root, 'node_modules', ...pkgName.split('/'));
  const binDir = path.join(pkgRoot, 'jre', 'bin');
  const libDir = path.join(pkgRoot, 'lib');
  fs.mkdirSync(binDir, {recursive: true});
  fs.mkdirSync(libDir, {recursive: true});
  fs.writeFileSync(path.join(pkgRoot, 'package.json'), JSON.stringify({name: pkgName}));
  const jar = path.join(libDir, 'safe-db-mcp.jar');
  if (withJar) {
    fs.writeFileSync(jar, 'jar');
  }
  const javaName = process.platform === 'win32' ? 'java.exe' : 'java';
  const java = path.join(binDir, javaName);
  if (withJava) {
    fs.writeFileSync(java, javaContent);
    if (javaMode !== undefined) {
      fs.chmodSync(java, javaMode);
    }
  }
  return {root, nodeModules: path.join(root, 'node_modules'), java, jar};
}

test('missing platform package fails on stderr with an npx example', async () => {
  const emptyModules = fs.mkdtempSync(path.join(os.tmpdir(), 'safedb-mcp-empty-'));
  try {
    const result = await runCli({NODE_PATH: emptyModules});
    assert.equal(result.code, 1);
    assert.equal(result.stdout, '');
    assert.match(result.stderr, /No bundled runtime/);
    assert.match(result.stderr, /npx -y @safe-db\/mcp/);
  } finally {
    fs.rmSync(emptyModules, {recursive: true, force: true});
  }
});

test('missing bundled java or jar fails on stderr with exit code 1', async () => {
  const noJava = setupFakePlatformPkg({withJava: false, withJar: true});
  try {
    const result = await runCli({NODE_PATH: noJava.nodeModules});
    assert.equal(result.code, 1);
    assert.match(result.stderr, /Bundled java not found/);
  } finally {
    fs.rmSync(noJava.root, {recursive: true, force: true});
  }

  const noJar = setupFakePlatformPkg({withJava: true, withJar: false});
  try {
    const result = await runCli({NODE_PATH: noJar.nodeModules});
    assert.equal(result.code, 1);
    assert.match(result.stderr, /Bundled jar not found/);
  } finally {
    fs.rmSync(noJar.root, {recursive: true, force: true});
  }
});

test('main propagates child exit code, signal exit, and spawn error on POSIX', {skip: process.platform === 'win32'}, async () => {
  const exitPkg = setupFakePlatformPkg({
    javaContent: `#!/usr/bin/env node\nprocess.exit(42);\n`,
    javaMode: 0o755,
  });
  try {
    const exitResult = await runCli({NODE_PATH: exitPkg.nodeModules}, ['--version']);
    assert.equal(exitResult.code, 42);
  } finally {
    fs.rmSync(exitPkg.root, {recursive: true, force: true});
  }

  const signalPkg = setupFakePlatformPkg({
    javaContent: `#!/usr/bin/env node\nprocess.kill(process.pid, 'SIGTERM');\n`,
    javaMode: 0o755,
  });
  try {
    const signalResult = await runCli({NODE_PATH: signalPkg.nodeModules}, []);
    assert.equal(signalResult.code, 1);
  } finally {
    fs.rmSync(signalPkg.root, {recursive: true, force: true});
  }

  const eaccesPkg = setupFakePlatformPkg({
    javaContent: `#!/usr/bin/env node\n`,
    javaMode: 0o644,
  });
  try {
    const errResult = await runCli({NODE_PATH: eaccesPkg.nodeModules}, []);
    assert.equal(errResult.code, 1);
    assert.match(errResult.stderr, /safe-db-mcp:/);
  } finally {
    fs.rmSync(eaccesPkg.root, {recursive: true, force: true});
  }
});

test('spawns bundled java with the jar, flags, and inherited stdio', () => {
  const {root, java, jar} = setupFakePlatformPkg();
  try {
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
    // require.resolve() realpaths module roots; macOS tmp is /var -> /private/var.
    assert.equal(spawned.command, fs.realpathSync(java));
    assert.deepEqual(spawned.args, [
      '-Dfile.encoding=UTF-8',
      '--enable-native-access=ALL-UNNAMED',
      '-jar',
      fs.realpathSync(jar),
      'connections',
      'list',
    ]);
    assert.deepEqual(spawned.options, {stdio: 'inherit'});
  } finally {
    fs.rmSync(root, {recursive: true, force: true});
  }
});
