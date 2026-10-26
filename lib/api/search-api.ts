import type {
    VideoSource,
    VideoItem,
    ApiSearchResponse,
} from '@/lib/types';
import { fetchWithTimeout, withRetry, buildSourceHeaders } from './http-utils';

/**
 * 构造源站的查询 URL。
 * searchPath 为空时直接用 baseUrl (与 convertToVideoSource 的行为一致)。
 */
function buildQueryUrl(source: VideoSource, query: string, page: number, ac: 'detail' | 'list'): string {
    const url = new URL(`${source.baseUrl}${source.searchPath || ''}`);
    url.searchParams.set('ac', ac);
    url.searchParams.set('wd', query);
    url.searchParams.set('pg', page.toString());
    return url.toString();
}

/**
 * 单次尝试某个源。
 * 返回 { ok, data } — 失败时 data 为 null, 不抛异常(便于降级)。
 */
async function attemptSource(
    source: VideoSource,
    query: string,
    page: number,
    ac: 'detail' | 'list',
    uaIndex: number,
    signal?: AbortSignal
): Promise<{ ok: boolean; data: ApiSearchResponse | null; error?: string }> {
    const url = buildQueryUrl(source, query, page, ac);

    try {
        const response = await withRetry(async () => {
            const res = await fetchWithTimeout(url, {
                method: 'GET',
                headers: {
                    ...buildSourceHeaders(url, uaIndex),
                    ...(source.headers || {}),
                },
                signal,
            });

            if (!res.ok) {
                throw new Error(`HTTP ${res.status}: ${res.statusText}`);
            }

            return res;
        });

        const data: ApiSearchResponse = await response.json();

        if (data.code !== 1 && data.code !== 0) {
            return { ok: false, data: null, error: data.msg || 'Invalid API response' };
        }

        return { ok: true, data };
    } catch (error) {
        const msg = error instanceof Error ? error.message : 'Unknown error';
        // AbortError 直接上抛, 不做降级 (用户已取消/超时)
        if (msg === 'The operation was aborted' || (error as Error & { name?: string })?.name === 'AbortError') {
            throw error;
        }
        return { ok: false, data: null, error: msg };
    }
}

/**
 * Search videos from a single source
 *
 * 2026-10-25 「流畅观看」优化:
 *   实测 31 个源: ac=detail 成功 8 个, ac=list 成功 11 个, 两者互补。
 *   改为 detail 失败自动降级 list, 并用完整浏览器 UA + 反爬头替代裸 'Mozilla/5.0'。
 */
async function searchVideosBySource(
    query: string,
    source: VideoSource,
    page: number = 1,
    signal?: AbortSignal
): Promise<{ results: VideoItem[]; source: string; responseTime: number; pagecount: number }> {
    const startTime = Date.now();

    // detail 优先(带播放地址, 供详情页直接用), 失败降级 list
    const attempts: Array<'detail' | 'list'> = ['detail', 'list'];
    let lastError = 'Unknown error';
    let payload: ApiSearchResponse | null = null;

    for (let i = 0; i < attempts.length; i++) {
        const result = await attemptSource(source, query, page, attempts[i], i, signal);
        if (result.ok && result.data) {
            payload = result.data;
            break;
        }
        if (result.error) {
            lastError = result.error;
        }
    }

    if (!payload) {
        console.error(`Search failed for source ${source.name}:`, lastError);
        throw {
            code: 'SEARCH_FAILED',
            message: `Failed to search from ${source.name}`,
            source: source.id,
            retryable: true,
        };
    }

    const results: VideoItem[] = (payload.list || []).map(item => ({
        ...item,
        source: source.id,
    }));

    return {
        results,
        source: source.id,
        responseTime: Date.now() - startTime,
        pagecount: payload.pagecount ?? 1,
    };
}


/**
 * Search videos from multiple sources in parallel
 */
export async function searchVideos(
    query: string,
    sources: VideoSource[],
    page: number = 1,
    signal?: AbortSignal
): Promise<Array<{ results: VideoItem[]; source: string; responseTime?: number; pagecount?: number; error?: string }>> {
    const searchPromises = sources.map(async (source) => {
        try {
            return await searchVideosBySource(query, source, page, signal);
        } catch (error) {
            return {
                results: [],
                source: source.id,
                error: error instanceof Error ? error.message : 'Unknown error',
            };
        }
    });

    return Promise.all(searchPromises);
}
