import Ajv2020 from "ajv/dist/2020.js";
import addFormats from "ajv-formats";
import fs from "fs";

const schemaPath = process.argv[2];
const dataPath = process.argv[3];

if (!schemaPath || !dataPath) {
  console.error("Usage: node validate.js <schema.json> <data.json>");
  process.exit(1);
}

const schema = JSON.parse(fs.readFileSync(schemaPath, "utf8"));
const data = JSON.parse(fs.readFileSync(dataPath, "utf8"));

const ajv = new Ajv2020({ allErrors: true });
addFormats(ajv);

const validate = ajv.compile(schema);
if (validate(data)) {
  console.log(`ok   ${dataPath}`);
  process.exit(0);
} else {
  console.log(`FAIL ${dataPath}`);
  console.log(JSON.stringify(validate.errors, null, 2));
  process.exit(1);
}
