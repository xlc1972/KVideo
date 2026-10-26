/**
 * GET /api/tv-update
 *
 * Android TV 客户端的自动更新检查端点。
 *
 * 返回:
 * {
 *   "version": "4.9.21",
 *   "versionCode": 21,
 *   "url": "http://106.15.153.201/ziqidonglai/latest.apk",
 *   "sha256": "…",
 *   "size": 2271335,
 *   "publishedAt": "2026-10-25T…",
 *   "notes": "…",
 *   "mandatory": false
 * }
 *
 * 数据来源: /opt/kvideo/tv-update.json (由部署脚本维护)
 * 若文件不存在则返回 404, 客户端据此回退到旧版本, 不影响使用。
 */
import { NextResponse } from 'next/server';
import { readFile, stat } from 'fs/promises';
import path from 'path';

export const dynamic = 'force-dynamic';

const UPDATE_MANIFEST = process.env.TV_UPDATE_MANIFEST || '/opt/kvideo/tv-update.json';

export async function GET() {
    try {
        let raw: string;
        try {
            raw = await readFile(UPDATE_MANIFEST, 'utf-8');
        } catch {
            // manifest 不存在 -> 没有可用更新
            return NextResponse.json(
                { error: 'update manifest not found' },
                { status: 404 }
            );
        }

        const manifest = JSON.parse(raw);

        // 允许 manifest 里省略 sha256/size, 由服务端补算 (小文件直接读)
        const sha256 = manifest.sha256 || '';
        let size = manifest.size || 0;

        if (manifest.apkPath && (!sha256 || !size)) {
            try {
                const abs = path.resolve(manifest.apkPath);
                const st = await stat(abs);
                size = size || st.size;
            } catch {
                // 文件不可读, 保留原值
            }
        }

        if (!manifest.url) {
            return NextResponse.json(
                { error: 'update manifest has no url' },
                { status: 404 }
            );
        }

        return NextResponse.json(
            {
                version: manifest.version || '',
                versionCode: manifest.versionCode || 0,
                url: manifest.url,
                sha256,
                size,
                publishedAt: manifest.publishedAt || new Date().toISOString(),
                notes: manifest.notes || '',
                mandatory: Boolean(manifest.mandatory),
            },
            {
                headers: {
                    'Cache-Control': 'no-store, no-cache, must-revalidate',
                },
            }
        );
    } catch (e) {
        console.error('[tv-update] failed:', e);
        return NextResponse.json(
            { error: 'update check failed' },
            { status: 500 }
        );
    }
}
