#!/usr/bin/env node
// Compose a federated supergraph SDL from per-subgraph `_service.sdl` files.
//
// Usage:
//   node compose.js <name=path-to-sdl> [<name=path-to-sdl> ...]
//
// On success: prints the composed supergraph SDL to stdout, exits 0.
// On failure: prints `[error] ...` lines (and any extension code) to stderr, exits 1.
//
// This script is invoked by the Kotlin composer CLI (`com.travelgraph.composer.Main`).
// It is the SINGLE place where @apollo/composition is consumed -- all other code in this
// repository works with the resulting supergraph SDL string.

'use strict';

const { composeServices } = require('@apollo/composition');
const { parse } = require('graphql');
const fs = require('fs');

if (process.argv.length < 3) {
  console.error('[error] no subgraphs provided. Expected: <name=path> [<name=path> ...]');
  process.exit(2);
}

const services = [];
for (const arg of process.argv.slice(2)) {
  const sep = arg.indexOf('=');
  if (sep <= 0) {
    console.error(`[error] malformed subgraph argument '${arg}'. Expected name=path.`);
    process.exit(2);
  }
  const name = arg.substring(0, sep);
  const path = arg.substring(sep + 1);
  let sdl;
  try {
    sdl = fs.readFileSync(path, 'utf8');
  } catch (err) {
    console.error(`[error] could not read SDL for '${name}' at ${path}: ${err.message}`);
    process.exit(2);
  }
  let typeDefs;
  try {
    typeDefs = parse(sdl);
  } catch (err) {
    console.error(`[error] subgraph '${name}' SDL did not parse: ${err.message}`);
    process.exit(1);
  }
  services.push({ name, typeDefs, url: `local://${name}` });
}

const result = composeServices(services);

if (result.errors && result.errors.length > 0) {
  console.error(`[error] composition failed with ${result.errors.length} error(s):`);
  for (const err of result.errors) {
    const code = err.extensions && err.extensions.code ? ` (${err.extensions.code})` : '';
    console.error(`  - ${err.message}${code}`);
  }
  process.exit(1);
}

if (!result.supergraphSdl) {
  console.error('[error] composition returned no supergraphSdl and no errors. This is unexpected.');
  process.exit(1);
}

process.stdout.write(result.supergraphSdl);
if (!result.supergraphSdl.endsWith('\n')) process.stdout.write('\n');
