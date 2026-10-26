import type { NextConfig } from "next";
import { getLanAllowedDevOrigins } from "./lib/config/lan-access";

const lanAllowedDevOrigins = getLanAllowedDevOrigins();

const nextConfig: NextConfig = {
  ...(lanAllowedDevOrigins.length > 0 ? { allowedDevOrigins: lanAllowedDevOrigins } : {}),

  // 家庭净化版 - 显式注入 env 给 Edge runtime (Next standalone 在 workerd V8 isolate 里读不到 process.env)
  env: {
    ACCESS_PASSWORD: process.env.ACCESS_PASSWORD || "",
    ADMIN_PASSWORD: process.env.ADMIN_PASSWORD || "",
    // 订阅源必须是浏览器能直接访问的地址。
    // 之前这里填的是服务器内网 http://127.0.0.1:13080/family-sources.json,
    // 浏览器访问不到, 只能靠 /api/proxy 兜底, 而 proxy 因 SSRF 防护会拒绝内网地址。
    // 现在指向本站自己的 /api/sources (服务端读运行时源注册表, 返回浏览器可达的地址)。
    SUBSCRIPTION_SOURCES: process.env.SUBSCRIPTION_SOURCES || '[{"name":"紫气东来片源","url":"/api/sources"}]',
    AD_KEYWORDS_FILE: process.env.AD_KEYWORDS_FILE || "ad-keywords.txt",
    MERGE_SOURCES: process.env.MERGE_SOURCES || "true",
    IPTV_SOURCES: process.env.IPTV_SOURCES || "",
    DANMAKU_API_URL: process.env.DANMAKU_API_URL || "",
    NEXT_PUBLIC_SITE_NAME: process.env.NEXT_PUBLIC_SITE_NAME || "紫气东来影院",
    NEXT_PUBLIC_SITE_TITLE: process.env.NEXT_PUBLIC_SITE_TITLE || "紫气东来 - 家庭影视",
  },

  // Performance optimizations
  reactStrictMode: true,
  poweredByHeader: false,

  // Compiler optimizations
  compiler: {
    removeConsole: process.env.NODE_ENV === 'production'
  },

  output: 'standalone',
  outputFileTracingRoot: __dirname,
  turbopack: {
    root: __dirname,
  },

  images: {
    remotePatterns: [
      { protocol: 'https', hostname: 'img3.doubanio.com' },
      { protocol: 'https', hostname: 'img1.doubanio.com' },
      { protocol: 'https', hostname: 'img2.doubanio.com' },
      { protocol: 'https', hostname: 'img9.doubanio.com' },
      { protocol: 'http', hostname: '**.com' },
      { protocol: 'https', hostname: '**.com' },
      { protocol: 'http', hostname: '**.cn' },
      { protocol: 'https', hostname: '**.cn' },
      { protocol: 'http', hostname: '**.net' },
      { protocol: 'https', hostname: '**.net' },
      { protocol: 'http', hostname: '**.org' },
      { protocol: 'https', hostname: '**.org' },
      { protocol: 'http', hostname: '**.tv' },
      { protocol: 'https', hostname: '**.tv' },
      { protocol: 'http', hostname: '**.io' },
      { protocol: 'https', hostname: '**.io' },
      { protocol: 'http', hostname: '**.xyz' },
      { protocol: 'https', hostname: '**.xyz' },
      { protocol: 'http', hostname: '**.online' },
      { protocol: 'https', hostname: '**.online' },
      { protocol: 'http', hostname: '**.top' },
      { protocol: 'https', hostname: '**.top' },
    ],
    formats: ['image/webp'],
    minimumCacheTTL: 60,
  },
};

export default nextConfig;
