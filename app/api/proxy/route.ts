import { NextRequest, NextResponse } from 'next/server';
import { processM3u8Content } from '@/lib/utils/proxy-utils';
import { fetchWithRetry } from '@/lib/utils/fetch-with-retry';
import { checkProxyTarget } from '@/lib/utils/proxy-ssrf-guard';
import { getRuntimeFeatures } from '@/lib/server/runtime-features';

export const runtime = 'edge';

/**
 * 解析对外可达的 origin (scheme://host)。
 *
 * 为什么需要:
 *   Next standalone 以 Environment=HOSTNAME=0.0.0.0 启动,
 *   request.nextUrl.origin 因此是 "http://0.0.0.0:3001" —— 内部监听地址。
 *   用它重写 m3u8 里的分片 URL, 电视/浏览器拿到的地址无法访问,
 *   于是视频永远停在 readyState=0, 表现为"播放端遥控无反应"。
 *
 * 优先用反向代理传来的头 (nginx 已设置 X-Forwarded-Proto 与 Host),
 * 并拒绝 0.0.0.0 / localhost / 127.0.0.1 等内部地址。
 */
const INTERNAL_HOSTS = new Set([
    '0.0.0.0',
    '::',
    'localhost',
    '127.0.0.1',
    '::1',
    '0',
]);

function resolvePublicOrigin(request: NextRequest): string {
    const forwardedHost = request.headers.get('x-forwarded-host');
    const hostHeader = request.headers.get('host');
    const candidateHost = (forwardedHost || hostHeader || '').split(',')[0].trim();

    const forwardedProto = request.headers.get('x-forwarded-proto');
    const proto = (forwardedProto || '').split(',')[0].trim()
        || (request.nextUrl.protocol || 'https').replace(':', '');

    if (candidateHost) {
        // 剥掉端口后判断是否为内部地址
        const hostname = candidateHost.startsWith('[')
            ? candidateHost.slice(1, candidateHost.indexOf(']'))
            : candidateHost.split(':')[0];

        if (!INTERNAL_HOSTS.has(hostname.toLowerCase())) {
            return `${proto}://${candidateHost}`;
        }
    }

    // 头里没有可用外部地址时, 退回 nextUrl.origin
    return request.nextUrl.origin;
}


// Disable SSL verification for video sources with invalid certificates
// Note: This is not supported in Cloudflare Workers/Edge Runtime.
// process.env.NODE_TLS_REJECT_UNAUTHORIZED = '0';

export async function GET(request: NextRequest) {
    const runtimeFeatures = getRuntimeFeatures();

    if (!runtimeFeatures.mediaProxyEnabled) {
        return NextResponse.json(
            {
                error: 'External media proxy is disabled on this deployment',
                message: runtimeFeatures.restrictionSummary,
            },
            { status: 403 }
        );
    }

    const url = request.nextUrl.searchParams.get('url');

    if (!url) {
        return new NextResponse('Missing URL parameter', { status: 400 });
    }

    // SSRF 防护: 拒绝指向内网/保留地址的目标。
    // 站点自身会请求 /api/proxy?url=/family-sources.json 这类站点内相对地址,
    // 那类地址不含 http(s) scheme, 由下面的 protocol 判定一并挡掉。
    const targetCheck = checkProxyTarget(url);
    if (!targetCheck.ok) {
        console.warn(`[proxy] blocked target: ${targetCheck.reason} (${url.slice(0, 120)})`);
        return new NextResponse(
            JSON.stringify({ error: 'Proxy target not allowed', reason: targetCheck.reason }),
            { status: 403, headers: { 'Content-Type': 'application/json' } }
        );
    }

    try {
        // Extract headers to forward (only essential ones)
        const requestHeaders: Record<string, string> = {};
        const forwardHeaders = ['cookie', 'range'];

        forwardHeaders.forEach(key => {
            const value = request.headers.get(key);
            if (value) requestHeaders[key] = value;
        });

        const response = await fetchWithRetry({ url, request, headers: requestHeaders });

        // If upstream returned an error, pass it through with CORS headers
        if (!response.ok) {
            const errorText = await response.text();
            return new NextResponse(errorText || `Upstream error: ${response.status}`, {
                status: response.status,
                statusText: response.statusText,
                headers: {
                    'Content-Type': response.headers.get('Content-Type') || 'text/plain',
                    'Access-Control-Allow-Origin': '*',
                },
            });
        }

        const contentType = response.headers.get('Content-Type');

        // Better M3U8 detection: check both content-type and actual content
        const isM3u8ByHeader = contentType &&
            (contentType.includes('application/vnd.apple.mpegurl') ||
                contentType.includes('application/x-mpegurl')) ||
            url.endsWith('.m3u8');

        // For potential M3U8 files, check content
        if (isM3u8ByHeader || url.includes('.m3u8')) {
            const text = await response.text();

            // Verify it's actually M3U8 content (starts with #EXTM3U or #EXT-X-)
            if (text.trim().startsWith('#EXTM3U') || text.trim().startsWith('#EXT-X-')) {
                const modifiedText = await processM3u8Content(text, url, resolvePublicOrigin(request));

                return new NextResponse(modifiedText, {
                    status: response.status,
                    statusText: response.statusText,
                    headers: {
                        'Content-Type': 'application/vnd.apple.mpegurl',
                        'Access-Control-Allow-Origin': '*',
                        'Access-Control-Allow-Methods': 'GET, OPTIONS',
                        'Access-Control-Allow-Headers': 'Content-Type, Authorization',
                    },
                });
            }

            // Not M3U8 content, return as-is
            return new NextResponse(text, {
                status: response.status,
                statusText: response.statusText,
                headers: {
                    'Content-Type': contentType || 'text/plain',
                    'Access-Control-Allow-Origin': '*',
                },
            });
        }

        // For non-m3u8 content
        const headers = new Headers();
        response.headers.forEach((value, key) => {
            const lowerKey = key.toLowerCase();
            if (!['content-encoding', 'content-length', 'transfer-encoding'].includes(lowerKey)) {
                headers.set(key, value);
            }
        });

        headers.set('Access-Control-Allow-Origin', '*');
        headers.set('Access-Control-Allow-Methods', 'GET, OPTIONS');
        headers.set('Access-Control-Allow-Headers', 'Content-Type, Authorization');
        headers.set('Cache-Control', 'no-store, no-cache, must-revalidate, proxy-revalidate');

        return new NextResponse(response.body, {
            status: response.status,
            statusText: response.statusText,
            headers: headers,
        });
    } catch (error) {
        console.error('Proxy error:', error);
        return new NextResponse(
            JSON.stringify({
                error: 'Proxy request failed',
                message: error instanceof Error ? error.message : 'Unknown error',
                url: url
            }),
            {
                status: 500,
                headers: {
                    'Content-Type': 'application/json',
                    'Access-Control-Allow-Origin': '*',
                }
            }
        );
    }
}

export async function OPTIONS(request: NextRequest) {
    return new NextResponse(null, {
        status: 204,
        headers: {
            'Access-Control-Allow-Origin': '*',
            'Access-Control-Allow-Methods': 'GET, OPTIONS',
            'Access-Control-Allow-Headers': 'Content-Type, Authorization',
        },
    });
}
