import { test } from "node:test";
import assert from "node:assert/strict";
import { englishStyle } from "../../main/assets/map/labels.mjs";

function evaluate(expression, properties) {
  if (!Array.isArray(expression)) return expression;
  const [operator, ...args] = expression;
  const value = input => evaluate(input, properties);
  switch (operator) {
    case "get": return properties[args[0]] ?? null;
    case "coalesce": return args.map(value).find(item => item !== null);
    case "!=": return value(args[0]) !== value(args[1]);
    case "case":
      for (let index = 0; index < args.length - 1; index += 2) {
        if (value(args[index])) return value(args[index + 1]);
      }
      return value(args.at(-1));
    default: throw new Error(`Unexpected expression: ${operator}`);
  }
}

test("names prefer nonempty English then Latin, never local-script-only names", () => {
  const original = { layers: [{ type: "symbol", layout: { "text-field": ["get", "name"] } }] };
  const expression = englishStyle(original).layers[0].layout["text-field"];
  assert.equal(evaluate(expression, { "name:en": "Tokyo", name_en: "Other", "name:latin": "Latin" }), "Tokyo");
  assert.equal(evaluate(expression, { "name:en": "", name_en: "Delhi", "name:latin": "Latin" }), "Delhi");
  assert.equal(evaluate(expression, { "name:en": "", name_en: "", "name:latin": "Transliterated" }), "Transliterated");
  assert.equal(evaluate(expression, { name: "\u6771\u4eac", "name:nonlatin": "\u6771\u4eac" }), "");
  assert.deepEqual(original.layers[0].layout["text-field"], ["get", "name"]);
});

test("route shields and non-label layers are preserved", () => {
  const shield = { type: "symbol", layout: { "text-field": ["to-string", ["get", "ref"]] } };
  const road = { type: "line", paint: { "line-color": "white" } };
  const icon = { type: "symbol", layout: { "icon-image": "station" } };
  assert.deepEqual(englishStyle({ layers: [shield, road, icon] }).layers, [shield, road, icon]);
});
