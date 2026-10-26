/**
 * HTTP Utilities for API calls
 * Handles timeouts and retries
 */

// Disable SSL verification for video sources with invalid certificates
process.env.NODE_TLS_REJECT_UNAUTHORIZED = '0';

// 2026-10-25 「流畅观看」优化: 15s -> 8s
// 慢源(6-12s)会拖住搜索尾部(实测 65 源时整体 20s), 8s 足够覆盖绝大多数可用源
const REQUEST_TIMEOUT = 8000;
const MAX_RETRIES = 3;
const RETRY_DELAY = 200;

/**
 * 真实浏览器 UA 轮换池。
 * 实测: 裸 'Mozilla/5.0' 被部分源的反爬拦截, 换成完整浏览器 UA 后可救回多个源。
 */
export const UA_ROTATION: string[] = [
    'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
    'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
    'Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1',
];

/**
 * 构造带反爬头的请求头 (与 KVideo 源站访问方式对齐)。
 * Referer/Origin 指向源站自身, 并带 X-Forwarded-For 以通过常见风控。
 */
export function buildSourceHeaders(sourceUrl: string, uaIndex: number = 0): Record<string, string> {
    let origin = '';
    try {
        const u = new URL(sourceUrl);
        origin = `${u.protocol}//${u.host}`;
    } catch {
        origin = '';
    }

    const headers: Record<string, string> = {
        'User-Agent': UA_ROTATION[Math.abs(uaIndex) % UA_ROTATION.length],
        'Accept': '*/*',
        'Accept-Language': 'zh-CN,zh;q=0.9,en;q=0.8',
        'Connection': 'keep-alive',
    };

    if (origin) {
        headers['Referer'] = `${origin}/`;
        headers['Origin'] = origin;
    }

    headers['X-Forwarded-For'] = '202.108.22.5';
    headers['Client-IP'] = '202.108.22.5';
    headers['Sec-Fetch-Dest'] = 'empty';
    headers['Sec-Fetch-Mode'] = 'cors';
    headers['Sec-Fetch-Site'] = 'cross-site';

    return headers;
}

/**
 * Fetch with timeout support
 * Accepts an optional external AbortSignal for cancellation cascade.
 */
export async function fetchWithTimeout(
    url: string,
    options: RequestInit = {},
    timeout: number = REQUEST_TIMEOUT
): Promise<Response> {
    const controller = new AbortController();
    const timeoutId = setTimeout(() => controller.abort(), timeout);

    // If an external signal is provided, propagate its abort
    const externalSignal = options.signal;
    let onAbort: (() => void) | undefined;
    if (externalSignal) {
        if (externalSignal.aborted) {
            controller.abort();
        } else {
            onAbort = () => controller.abort();
            externalSignal.addEventListener('abort', onAbort, { once: true });
        }
    }

    try {
        const response = await fetch(url, {
            ...options,
            signal: controller.signal,
        });
        return response;
    } finally {
        clearTimeout(timeoutId);
        if (externalSignal && onAbort) {
            externalSignal.removeEventListener('abort', onAbort);
        }
    }
}

/**
 * Retry logic wrapper
 */
export async function withRetry<T>(
    fn: () => Promise<T>,
    retries: number = MAX_RETRIES
): Promise<T> {
    let lastError: Error | null = null;

    for (let i = 0; i <= retries; i++) {
        try {
            return await fn();
        } catch (error) {
            lastError = error as Error;

            if (lastError?.name === 'AbortError') {
                throw lastError;
            }

            if (i < retries) {
                await new Promise(resolve => setTimeout(resolve, RETRY_DELAY * (i + 1)));
            }
        }
    }

    throw lastError;
}
