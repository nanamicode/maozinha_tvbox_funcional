#!/usr/bin/env bash
set -euo pipefail
mkdir -p app/src/main/assets
curl -L --fail --retry 3 -o app/src/main/assets/hand_detection.tflite \
  https://raw.githubusercontent.com/hugocornellier/hand_detection/main/assets/models/hand_detection.tflite
curl -L --fail --retry 3 -o app/src/main/assets/hand_landmark_full.tflite \
  https://raw.githubusercontent.com/hugocornellier/hand_detection/main/assets/models/hand_landmark_full.tflite
echo "Models ready."

mkdir -p app/src/main/assets/web/vendor
cp web/index.html app/src/main/assets/web/index.html
cp web/style.css app/src/main/assets/web/style.css
cp web/app.js app/src/main/assets/web/app.js
curl -L --fail --retry 3 -o app/src/main/assets/web/vendor/three.module.js \
  https://cdn.jsdelivr.net/npm/three@0.180.0/build/three.module.js
cp shared/edge_profile.json app/src/main/assets/edge_profile.json
