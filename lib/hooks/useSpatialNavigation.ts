/**
 * useSpatialNavigation
 * Provides D-pad/arrow key based 2D spatial navigation for TV mode.
 * Finds all [data-focusable] elements and navigates between them
 * based on directional arrow key presses.
 */

import { useEffect, useCallback } from 'react';

function getRect(el: Element): DOMRect {
  return el.getBoundingClientRect();
}

function getCenter(rect: DOMRect): { x: number; y: number } {
  return {
    x: rect.left + rect.width / 2,
    y: rect.top + rect.height / 2,
  };
}

type Direction = 'up' | 'down' | 'left' | 'right';

function findBestCandidate(
  current: Element,
  candidates: Element[],
  direction: Direction
): Element | null {
  const currentRect = getRect(current);
  const currentCenter = getCenter(currentRect);

  let bestElement: Element | null = null;
  let bestScore = Infinity;

  for (const candidate of candidates) {
    if (candidate === current) continue;

    const candidateRect = getRect(candidate);
    const candidateCenter = getCenter(candidateRect);

    const dx = candidateCenter.x - currentCenter.x;
    const dy = candidateCenter.y - currentCenter.y;

    // Filter by direction
    let isInDirection = false;
    switch (direction) {
      case 'up':
        isInDirection = dy < -10;
        break;
      case 'down':
        isInDirection = dy > 10;
        break;
      case 'left':
        isInDirection = dx < -10;
        break;
      case 'right':
        isInDirection = dx > 10;
        break;
    }

    if (!isInDirection) continue;

    // Weighted distance: favor elements along the primary axis
    let score: number;
    if (direction === 'up' || direction === 'down') {
      score = Math.abs(dy) + Math.abs(dx) * 3;
    } else {
      score = Math.abs(dx) + Math.abs(dy) * 3;
    }

    if (score < bestScore) {
      bestScore = score;
      bestElement = candidate;
    }
  }

  return bestElement;
}

export function useSpatialNavigation(enabled: boolean) {
  const handleKeyDown = useCallback((e: KeyboardEvent) => {
    if (!enabled) return;

    const directionMap: Record<string, Direction> = {
      ArrowUp: 'up',
      ArrowDown: 'down',
      ArrowLeft: 'left',
      ArrowRight: 'right',
    };

    const direction = directionMap[e.key];

    // For input/textarea: allow Left/Right for cursor movement,
    // but let Up/Down navigate spatially so the user can escape the input on TV.
    // Skip if the event was already handled (e.g., by search history dropdown).
    const target = e.target as HTMLElement;
    const isInput = target.tagName === 'INPUT' ||
      target.tagName === 'TEXTAREA' ||
      target.isContentEditable;

    if (isInput) {
      if (!direction || direction === 'left' || direction === 'right') {
        return;
      }
      // If a React handler already called preventDefault (e.g., dropdown navigation), skip
      if (e.defaultPrevented) {
        return;
      }
    }

    if (direction) {
      // Check if the focused element is inside a [data-no-spatial] container
      const focused = document.activeElement as HTMLElement | null;
      if (focused?.closest('[data-no-spatial]')) return;

      // TV 可聚焦候选:
      //  1) [data-focusable]  —— 首页/搜索页/导航栏使用
      //  2) 原生可聚焦元素   —— 播放页 (EpisodeList 用 tabIndex, 按钮用 <button>) 原本一个都没有
      //     播放页之前 0 个候选 -> 方向键直接 return -> 遥控完全失效
      const FOCUSABLE_SELECTOR = [
        '[data-focusable]:not([disabled]):not([aria-hidden="true"])',
        'button:not([disabled]):not([aria-hidden="true"])',
        '[tabindex]:not([tabindex="-1"]):not([disabled]):not([aria-hidden="true"])',
        'a[href]:not([aria-hidden="true"])',
        'input:not([disabled]):not([aria-hidden="true"])',
        'select:not([disabled]):not([aria-hidden="true"])',
      ].join(', ');

      const focusableElements = Array.from(
        document.querySelectorAll(FOCUSABLE_SELECTOR)
      ).filter(el => {
        // Filter out elements inside [data-no-spatial]
        if (el.closest('[data-no-spatial]')) return false;
        // Filter out hidden elements
        const rect = getRect(el);
        if (rect.width <= 0 || rect.height <= 0) return false;
        // 排除 video/audio 及其内部元素 (占位但不可导航)
        if (el.closest('video, audio')) return false;
        return true;
      }).filter((el, i, arr) => {
        // 去重: 一个 button 内部若还有 button 不会发生, 但 input+label 可能重复
        return arr.indexOf(el) === i;
      });

      if (focusableElements.length === 0) return;

      const currentFocused = document.activeElement;
      const isAlreadyFocused = currentFocused && focusableElements.includes(currentFocused);

      if (!isAlreadyFocused) {
        // 焦点不在候选里: 优先选与当前焦点同区域的最接近元素, 否则第一个
        let target: HTMLElement | null = null;
        if (currentFocused && currentFocused !== document.body) {
          const cr = getRect(currentFocused);
          const cc = getCenter(cr);
          let bestScore = Infinity;
          for (const cand of focusableElements) {
            const cRect = getRect(cand);
            const cCenter = getCenter(cRect);
            const dx = Math.abs(cCenter.x - cc.x);
            const dy = Math.abs(cCenter.y - cc.y);
            const score = dx + dy * 2;
            if (score < bestScore) { bestScore = score; target = cand as HTMLElement; }
          }
        }
        if (!target) target = focusableElements[0] as HTMLElement;
        target.focus();
        e.preventDefault();
        return;
      }

      const best = findBestCandidate(currentFocused!, focusableElements, direction);
      if (best) {
        (best as HTMLElement).focus();
        best.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
        e.preventDefault();
      }
    } else if (e.key === 'Enter') {
      // Trigger activation on focused element
      // 原实现只认 data-focusable -> 播放页 Enter 无效
      const focused = document.activeElement as HTMLElement;
      if (!focused || focused === document.body) return;

      const isActivatable =
        focused.hasAttribute('data-focusable') ||
        focused.tagName === 'BUTTON' ||
        focused.tagName === 'A' ||
        focused.tagName === 'INPUT' ||
        focused.tagName === 'SELECT' ||
        focused.getAttribute('tabindex') !== null;

      if (isActivatable) {
        // 视频本身获得焦点时, Enter 切换播放/暂停
        if (focused.tagName === 'VIDEO') {
          const v = focused as HTMLVideoElement;
          if (v.paused) { void v.play().catch(() => {}); } else { v.pause(); }
          e.preventDefault();
          return;
        }
        focused.click();
        e.preventDefault();
      }
    }
  }, [enabled]);

  useEffect(() => {
    if (!enabled) return;

    document.addEventListener('keydown', handleKeyDown);
    return () => document.removeEventListener('keydown', handleKeyDown);
  }, [enabled, handleKeyDown]);
}
