import { NextResponse } from 'next/server';
import { getAllRuntimeSources } from '@/lib/server/runtime-source-registry';

export const runtime = 'edge';
export const dynamic = 'force-dynamic';

/**
 * 片源订阅列表 (公开可读)。
 *
 * 前端 "订阅源" 功能需要一个浏览器能直接访问的 JSON 地址。
 * 过去这里填的是 http://127.0.0.1:13080/family-sources.json ——
 * 那是服务器内网地址, 浏览器根本访问不到, 只能靠 /api/proxy 兜底,
 * 而 /api/proxy 出于 SSRF 防护必须拒绝内网地址。
 *
 * 这个端点由服务端直接读运行时源注册表, 返回浏览器可达的公网订阅地址。
 */
export async function GET() {
    try {
        const sources = await getAllRuntimeSources();
        return NextResponse.json(
            {
                version: 'runtime',
                name: '紫气东来片源',
                description: `${sources.length} 个片源 (运行时注册表)`,
                sources,
            },
            {
                headers: {
                    'Cache-Control': 'public, max-age=300, s-maxage=300',
                    'Access-Control-Allow-Origin': '*',
                },
            }
        );
    } catch (error) {
        console.error('[api/sources] failed to load runtime sources', error);
        return NextResponse.json(
            { error: 'Failed to load sources' },
            { status: 500 }
        );
    }
}
