/**
 * Runtime Video Source Registry
 *
 * 为什么需要:
 *   DEFAULT_SOURCES / PREMIUM_SOURCES 是编译期静态列表, 当前为空数组。
 *   真实片源来自 /opt/kvideo/family-sources.json (由 13080 静态服务提供, 每日 03:30 自动拉取)。
 *   服务端 /api/detail 只拿到 source id, 需要反查源配置 (baseUrl 等) 才能取到 m3u8。
 *   静态表为空 => 反查永远失败 => 播放端拿不到视频地址。
 *
 * 实现: Edge runtime 不能用 fs, 因此用 fetch 拉取本地静态服务, 带 TTL 缓存。
 *   缓存 TTL 60s: 既能跟随每日自动拉取的新源, 又不会每次请求都打一次本地 HTTP。
 */

import type { VideoSource } from '@/lib/types';

const SOURCES_URL = process.env.RUNTIME_SOURCES_URL || 'http://127.0.0.1:13080/family-sources.json';
const CACHE_TTL_MS = 60_000;
const FETCH_TIMEOUT_MS = 8_000;

interface CachedSources {
  byId: Map<string, VideoSource>;
  fetchedAt: number;
}

let cache: CachedSources | null = null;
let inflight: Promise<CachedSources> | null = null;

/**
 * The raw shape of an entry in the runtime sources JSON file. Only the fields that are
 * read are described; everything else is ignored.
 */
interface RuntimeSourceEntry {
  id?: unknown;
  name?: unknown;
  baseUrl?: unknown;
  searchPath?: unknown;
  detailPath?: unknown;
  sources?: unknown;
}

function isRuntimeSourceEntry(value: unknown): value is RuntimeSourceEntry {
  return typeof value === 'object' && value !== null;
}

function normalizeRuntimeSource(raw: unknown): VideoSource | null {
  if (!isRuntimeSourceEntry(raw)) return null;
  if (typeof raw.id !== 'string' || !raw.id) return null;
  if (typeof raw.baseUrl !== 'string' || !raw.baseUrl) return null;

  return {
    id: raw.id,
    name: typeof raw.name === 'string' && raw.name ? raw.name : raw.id,
    baseUrl: raw.baseUrl,
    // 运行时源没有单独的搜索/详情路径, 直接用 baseUrl (与 convertToVideoSource 行为一致)
    searchPath: typeof raw.searchPath === 'string' ? raw.searchPath : '',
    detailPath: typeof raw.detailPath === 'string' ? raw.detailPath : '',
  } as VideoSource;
}

async function fetchRuntimeSources(): Promise<CachedSources> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), FETCH_TIMEOUT_MS);
  try {
    const response = await fetch(SOURCES_URL, {
      signal: controller.signal,
      headers: { 'User-Agent': 'KVideo-RuntimeSourceRegistry/1.0' },
      cache: 'no-store',
    });
    if (!response.ok) {
      throw new Error(`runtime sources HTTP ${response.status}`);
    }
    const payload: unknown = await response.json();
    const list: unknown[] = Array.isArray(payload)
      ? payload
      : (isRuntimeSourceEntry(payload) && Array.isArray(payload.sources) ? payload.sources : []);
    const byId = new Map<string, VideoSource>();
    for (const raw of list) {
      const normalized = normalizeRuntimeSource(raw);
      if (normalized) byId.set(normalized.id, normalized);
    }
    if (byId.size === 0) {
      throw new Error('runtime sources empty');
    }
    return { byId, fetchedAt: Date.now() };
  } finally {
    clearTimeout(timer);
  }
}

async function getRuntimeSources(): Promise<CachedSources> {
  const now = Date.now();
  if (cache && now - cache.fetchedAt < CACHE_TTL_MS) {
    return cache;
  }
  if (!inflight) {
    inflight = fetchRuntimeSources()
      .then((result) => {
        cache = result;
        return result;
      })
      .catch((error) => {
        // 拉取失败时保留旧缓存 (宁可用过期数据, 也不要让播放全挂)
        console.error('[runtime-source-registry] fetch failed:', error);
        if (cache) return cache;
        throw error;
      })
      .finally(() => {
        inflight = null;
      });
  }
  return inflight;
}

/**
 * 按 id 查运行时源配置。找不到返回 undefined。
 */
export async function getRuntimeSourceById(id: string): Promise<VideoSource | undefined> {
  if (!id) return undefined;
  try {
    const { byId } = await getRuntimeSources();
    return byId.get(id);
  } catch {
    return undefined;
  }
}

/**
 * 取全部运行时源 (调试/探活用)。
 */
export async function getAllRuntimeSources(): Promise<VideoSource[]> {
  try {
    const { byId } = await getRuntimeSources();
    return Array.from(byId.values());
  } catch {
    return [];
  }
}
