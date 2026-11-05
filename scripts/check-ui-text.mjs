// The words of the window of the Linux and Windows app, checked without starting it: every key the code asks for exists, English and
// Dutch have the same keys, and a sentence has the same places to fill in both languages. A key that is missing shows up as the key
// itself in front of a person, and a key missing in Dutch shows English without anyone noticing.
//
//     node scripts/check-ui-text.mjs

import { readdirSync, readFileSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import vm from "node:vm";

const ui = join(dirname(fileURLToPath(import.meta.url)), "..", "windows", "ui");
const source = readFileSync(join(ui, "js", "i18n.js"), "utf8");
const end = source.indexOf("const tables");
if (end < 0) throw new Error("i18n.js has no line with const tables: this check needs to follow it");
const { en, nl } = vm.runInNewContext(source.slice(0, end) + "\n;({ en, nl })");

const problems = [];
const base = (key) => key.split("@")[0];
const places = (text) => [...text.matchAll(/\{\d\}/g)].map((m) => m[0]).sort().join();

for (const key of Object.keys(en)) {
  if (!(key in nl)) problems.push(`"${key}" has no Dutch: ${en[key]}`);
  else if (places(en[key]) !== places(nl[key])) problems.push(`"${key}" has other places to fill in English and Dutch: ${en[key]} / ${nl[key]}`);
}
for (const key of Object.keys(nl)) {
  if (!(key in en)) problems.push(`"${key}" is Dutch only: ${nl[key]}`);
}

const known = new Set(Object.keys(en).map(base));
const js = join(ui, "js");
for (const file of readdirSync(js).filter((name) => name.endsWith(".js") && name !== "mock.js" && name !== "i18n.js")) {
  const code = readFileSync(join(js, file), "utf8");
  for (const match of code.matchAll(/(?<![\w.])t\("([a-z0-9_]+)"\s*[,)]/g)) {
    if (!known.has(match[1])) problems.push(`${file} asks for "${match[1]}", which does not exist`);
  }
}

for (const problem of problems) console.log(problem);
console.log(`${Object.keys(en).length} English and ${Object.keys(nl).length} Dutch texts, ${problems.length} problem(s)`);
process.exit(problems.length ? 1 : 0);
