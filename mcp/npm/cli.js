#!/usr/bin/env node
'use strict';

const {spawn} = require('child_process');
const fs = require('fs');
const path = require('path');

function fail(message) {
  process.stderr.write(`safe-db-mcp: ${message}\n`);
  process.exit(1);
}

function isMuslLinux() {
  if (process.platform !== 'linux') {
    return false;
  }
  try {
    const report = process.report.getReport();
    return !report.header || !report.header.glibcVersionRuntime;
  } catch {
    return false;
  }
}

function platformPackageName() {
  return `@safe-db/mcp-${process.platform}-${process.arch}`;
}

function resolvePlatformRoot(modulePaths) {
  if (isMuslLinux()) {
    fail(
      'Alpine/musl Linux is not supported. Use glibc Linux (Debian, Ubuntu, RHEL, Fedora).\n' +
        '  npx -y @safe-db/mcp',
    );
  }
  const name = platformPackageName();
  try {
    const resolved = modulePaths
      ? require.resolve(`${name}/package.json`, {paths: modulePaths})
      : require.resolve(`${name}/package.json`);
    return path.dirname(resolved);
  } catch {
    fail(
      `No bundled runtime for ${process.platform}-${process.arch}. Install ${name}.\n` +
        '  npx -y @safe-db/mcp',
    );
  }
}

function launch({
  spawn: spawnImpl = spawn,
  modulePaths,
  argv = process.argv.slice(2),
} = {}) {
  const root = resolvePlatformRoot(modulePaths);
  const javaName = process.platform === 'win32' ? 'java.exe' : 'java';
  const java = path.join(root, 'jre', 'bin', javaName);
  const jar = path.join(root, 'lib', 'safe-db-mcp.jar');
  if (!fs.existsSync(java)) {
    fail(`Bundled java not found at ${java}`);
  }
  if (!fs.existsSync(jar)) {
    fail(`Bundled jar not found at ${jar}`);
  }
  return spawnImpl(
    java,
    [
      '-Dfile.encoding=UTF-8',
      '--enable-native-access=ALL-UNNAMED',
      '-jar',
      jar,
      ...argv,
    ],
    {stdio: 'inherit'},
  );
}

function main() {
  const child = launch();
  child.on('error', (error) => fail(error.message));
  child.on('exit', (code, signal) => {
    if (signal) {
      process.exit(1);
    }
    process.exit(code ?? 1);
  });
}

if (require.main === module) {
  main();
}

module.exports = {launch};
