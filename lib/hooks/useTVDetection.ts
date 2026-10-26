/**
 * useTVDetection
 * Detects if the user is on a TV/set-top-box browser.
 */

import { useState } from 'react';

const TV_USER_AGENT_PATTERNS = [
  /smarttv/i,
  /tizen/i,
  /webos/i,
  /firetv/i,
  /android tv/i,
  /googletv/i,
  /crkey/i, // Chromecast
  /aftt/i, // Amazon Fire TV Stick
  /aftm/i, // Amazon Fire TV
  /bravia/i, // Sony Bravia
  /netcast/i, // LG NetCast
  /viera/i, // Panasonic Viera
  /hbbtv/i,
];

/**
 * Detect whether the current browser is a TV / set-top-box browser.
 * Returns `false` when there is no browser (server side rendering).
 */
function detectTV(): boolean {
  if (typeof window === 'undefined' || typeof navigator === 'undefined') return false;

  const ua = navigator.userAgent;

  // Check UA for TV indicators
  const uaMatch = TV_USER_AGENT_PATTERNS.some(pattern => pattern.test(ua));
  if (uaMatch) return true;

  // Fallback heuristic: large screen + no touch + low pixel density
  const isLargeScreen = window.innerWidth >= 1280;
  const hasNoTouch = !('ontouchstart' in window) && navigator.maxTouchPoints === 0;
  const lowDensity = window.devicePixelRatio <= 1.5;

  return isLargeScreen && hasNoTouch && lowDensity;
}

export function useTVDetection(): boolean {
  // The detection result never changes for the lifetime of the page, so it is computed
  // once during the initial render instead of being set from an effect.
  const [isTV] = useState(detectTV);

  return isTV;
}
