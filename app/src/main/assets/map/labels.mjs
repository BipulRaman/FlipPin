export function englishStyle(style) {
  const englishName = ["case",
    ["!=", ["coalesce", ["get", "name:en"], ""], ""], ["get", "name:en"],
    ["!=", ["coalesce", ["get", "name_en"], ""], ""], ["get", "name_en"],
    ["coalesce", ["get", "name:latin"], ""]
  ];
  return {
    ...style,
    layers: style.layers.map(layer => {
      if (layer.type !== "symbol" || !JSON.stringify(layer.layout?.["text-field"])?.includes("name")) {
        return layer;
      }
      return { ...layer, layout: { ...layer.layout, "text-field": englishName } };
    })
  };
}
