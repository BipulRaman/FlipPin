import { englishStyle } from "./labels.mjs";

const generation = Number(new URL(location.href).searchParams.get("generation"));
const message = document.getElementById("message");
let revision = 0;
let syncing = false;
let failed = false;

function notify(type, data = {}) {
  window.FlipPin.postMessage(JSON.stringify({ type, generation, revision, ...data }));
}

function fail(error) {
  console.error(error);
  failed = true;
  message.textContent = "Map unavailable. Tap Retry below, update Android System WebView, or enter coordinates.";
  message.hidden = false;
  notify("error", { message: String(error) });
}

window.addEventListener("unhandledrejection", event => fail(event.reason));
window.addEventListener("error", event => fail(event.message));

try {
  const { Map, Marker, setWorkerUrl } = await import("./vendor/maplibre-gl.mjs");
  setWorkerUrl(new URL("./vendor/maplibre-gl-worker.mjs", import.meta.url).href);
  const response = await fetch("https://tiles.openfreemap.org/styles/liberty");
  if (!response.ok) throw new Error(`Map style request failed (${response.status}).`);
  const map = new Map({
    container: "map",
    style: englishStyle(await response.json()),
    center: [0, 20], zoom: 2, maxZoom: 22,
    attributionControl: false, pitchWithRotate: false,
    dragRotate: false, touchPitch: false
  });
  map.touchZoomRotate.disableRotation();
  const element = document.createElement("div");
  element.className = "pin";
  element.setAttribute("aria-label", "Destination pin. Drag to adjust.");
  element.appendChild(document.createElement("span"));
  const marker = new Marker({ element, draggable: true, anchor: "bottom", offset: [0, 5] });
  let markerVisible = false;

  function pick(point) {
    const latitude = Math.round(point.lat * 1e6) / 1e6;
    const longitude = Math.round(point.lng * 1e6) / 1e6;
    marker.setLngLat([longitude, latitude]);
    if (!markerVisible) marker.addTo(map);
    markerVisible = true;
    notify("pick", { latitude, longitude });
  }

  map.on("click", event => pick(event.lngLat));
  marker.on("drag", () => pick(marker.getLngLat()));
  marker.on("dragend", () => pick(marker.getLngLat()));
  map.on("moveend", () => {
    if (syncing) return;
    const center = map.getCenter();
    notify("camera", { latitude: center.lat, longitude: center.lng, zoom: map.getZoom() });
  });
  map.on("error", event => fail(event.error));
  map.once("load", () => {
    if (!failed) {
      message.hidden = true;
      notify("loaded");
    }
  });
  map.getCanvas().addEventListener("webglcontextlost", () => fail("WebGL context lost."));

  window.FlipPinMap = {
    setState(state) {
      revision = state.revision;
      syncing = true;
      try {
        if (state.point) {
          marker.setLngLat([state.point.longitude, state.point.latitude]);
          if (!markerVisible) marker.addTo(map);
          markerVisible = true;
        } else {
          marker.remove();
          markerVisible = false;
        }
        if (state.camera) {
          map.jumpTo({
            center: [state.camera.longitude, state.camera.latitude], zoom: state.camera.zoom
          });
        }
      } finally {
        syncing = false;
      }
    }
  };
  notify("ready");
} catch (error) {
  fail(error);
}
