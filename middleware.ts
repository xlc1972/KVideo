import { NextResponse } from 'next/server';
import type { NextRequest } from 'next/server';

/**
 * Family-safe guard:
 * - 屏蔽 /premium* 路由(返回 404)
 * - 屏蔽 /api/premium* (返回 204)
 * - 屏蔽 /api/user/sync(避免 Upstash 命中,即使没配也会走兜底 204)
 * - 屏蔽 /api/user/config(同上)
 *
 * 为什么需要这层:
 * - KVideo 主仓"配置隔离"假设 PREMIUM_SOURCES 为空时 UI 自动隐藏,但:
 *   1) 路由 /premium 仍可被直接 URL 命中
 *   2) Premium API 路由仍可被命中
 * - 家庭场景宁愿 404/204,也不要露出 UI 骨架
 */
export function middleware(request: NextRequest) {
  const { pathname } = request.nextUrl;

  // Premium 区页面路由 → 重定向到首页(更友好,而不是裸 404)
  if (pathname === '/premium' || pathname.startsWith('/premium/')) {
    const url = request.nextUrl.clone();
    url.pathname = '/';
    return NextResponse.redirect(url, 302);
  }

  // Premium API → 204 No Content(假装存在但不返回内容)
  if (pathname.startsWith('/api/premium')) {
    return new NextResponse(null, { status: 204 });
  }

  // Upstash 跨设备同步(家庭单 ECS,不需要)
  if (pathname === '/api/user/sync' || pathname === '/api/user/config') {
    return new NextResponse(null, { status: 204 });
  }

  return NextResponse.next();
}

export const config = {
  matcher: [
    '/premium/:path*',
    '/api/premium/:path*',
    '/api/user/sync',
    '/api/user/config',
  ],
};