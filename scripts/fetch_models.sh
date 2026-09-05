#!/usr/bin/env bash
set -euo pipefail
mkdir -p app/src/main/assets
curl -L --fail --retry 3 -o app/src/main/assets/hand_detection.tflite \
  https://raw.githubusercontent.com/hugocornellier/hand_detection/main/assets/models/hand_detection.tflite
curl -L --fail --retry 3 -o app/src/main/assets/hand_landmark_full.tflite \
  https://raw.githubusercontent.com/hugocornellier/hand_detection/main/assets/models/hand_landmark_full.tflite
echo "Models ready."
