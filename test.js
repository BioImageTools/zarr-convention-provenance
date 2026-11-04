// Every file under examples/ must validate against schema.json; every file under examples/invalid/ must not.
import { execFileSync } from "child_process";
import fs from "fs";
import path from "path";

function run(file) {
  try {
    execFileSync("node", ["validate.js", "schema.json", file], { stdio: "pipe" });
    return true;
  } catch {
    return false;
  }
}

let failed = 0;
for (const name of fs.readdirSync("examples").filter((f) => f.endsWith(".json"))) {
  const file = path.join("examples", name);
  const ok = run(file);
  console.log(`${ok ? "ok  " : "FAIL"} ${file}`);
  if (!ok) failed++;
}
const invalidDir = path.join("examples", "invalid");
if (fs.existsSync(invalidDir)) {
  for (const name of fs.readdirSync(invalidDir).filter((f) => f.endsWith(".json"))) {
    const file = path.join(invalidDir, name);
    const ok = run(file);
    console.log(`${ok ? "FAIL" : "ok  "} ${file} (expected invalid)`);
    if (ok) failed++;
  }
}
process.exit(failed ? 1 : 0);
