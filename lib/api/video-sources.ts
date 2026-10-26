/**
 * Video Source Configuration and Management
 * Handles third-party video API sources with validation and health checks
 */

import type { VideoSource } from '@/lib/types';
import { DEFAULT_SOURCES } from './default-sources';
import { PREMIUM_SOURCES } from './premium-sources';

/**
 * Get source by ID from both default and premium sources.
 *
 * 静态表 (DEFAULT_SOURCES / PREMIUM_SOURCES) 在当前部署中为空 ——
 * 真实片源由 /opt/kvideo/family-sources.json 运行时提供 (65 源, 每日自动拉取)。
 * 因此这里在静态表查不到时, 回退到运行时源注册表。
 * 否则 /api/detail 收到 source id 会反查失败 => 拿不到 m3u8 => 播放端完全不可用。
 */
export async function getSourceById(id: string): Promise<VideoSource | undefined> {
  if (!id) return undefined;

  // Search in default sources first
  const defaultSource = DEFAULT_SOURCES.find(source => source.id === id);
  if (defaultSource) {
    return defaultSource;
  }

  // Search in premium sources
  const premiumSource = PREMIUM_SOURCES.find(source => source.id === id);
  if (premiumSource) {
    return premiumSource;
  }

  // Fall back to the runtime source registry (family-sources.json)
  const { getRuntimeSourceById } = await import('@/lib/server/runtime-source-registry');
  return getRuntimeSourceById(id);
}
