/**
 * 代理目标地址的 SSRF 防护。
 *
 * /api/proxy 接受任意 url 参数并代为请求。目标指向内网时, 攻击者可以
 * 通过本站读取服务器自身或同一内网的其他服务 (云元数据、内部 API)。
 * 这里在发起请求前把内部地址挡掉。
 *
 * 判定只做"明显是内网"的拦截, 不做 DNS 解析 —— 解析结果会随时间变化,
 * 容易出现 TOCTOU。真正需要 DNS 级防护的场景应交给网络层 (安全组/iptables)。
 */

/** 明确禁止的主机名 */
const BLOCKED_HOSTNAMES = new Set([
    'localhost',
    'localhost.localdomain',
    'ip6-localhost',
    'ip6-loopback',
    '0.0.0.0',
    '0',
    '::',
    '::1',
    '0x7f000001',
    '0177.0.0.1',
]);

/** 只允许的协议 */
const ALLOWED_PROTOCOLS = new Set(['http:', 'https:']);

/**
 * IPv4 是否落在私有/保留网段。
 *
 * 覆盖 RFC1918、环回、链路本地、0.0.0.0/8、CGNAT (100.64/10)、
 * 以及云厂商元数据地址 169.254.169.254。
 */
export function isPrivateIPv4(host: string): boolean {
    const parts = host.split('.');
    if (parts.length !== 4) return false;

    const nums = parts.map((p) => {
        // 只接受十进制, 拒绝 0x / 八进制 等变形
        if (!/^\d{1,3}$/.test(p)) return -1;
        const n = Number(p);
        return n >= 0 && n <= 255 ? n : -1;
    });
    if (nums.some((n) => n < 0)) return false;

    const [a, b] = nums as [number, number, number, number];

    if (a === 0) return true;                       // 0.0.0.0/8
    if (a === 10) return true;                      // 10.0.0.0/8
    if (a === 127) return true;                     // 127.0.0.0/8
    if (a === 169 && b === 254) return true;         // 169.254.0.0/16 (含元数据)
    if (a === 172 && b >= 16 && b <= 31) return true; // 172.16.0.0/12
    if (a === 192 && b === 168) return true;         // 192.168.0.0/16
    if (a === 100 && b >= 64 && b <= 127) return true; // 100.64.0.0/10 (CGNAT)
    if (a >= 224) return true;                       // 组播/保留

    return false;
}

/**
 * IPv6 是否是内部地址。
 *
 * 处理 IPv4 映射形式 (::ffff:127.0.0.1) 和常见内部段。
 */
export function isPrivateIPv6(host: string): boolean {
    const h = host.toLowerCase();

    // IPv4 映射/兼容形式: ::ffff:127.0.0.1
    const mapped = h.match(/^::(?:ffff:)?(\d+\.\d+\.\d+\.\d+)$/);
    if (mapped) return isPrivateIPv4(mapped[1]);

    if (h === '::1' || h === '::' ) return true;
    // 唯一本地地址 fc00::/7
    if (/^f[cd][0-9a-f]{2}:/.test(h)) return true;
    // 链路本地 fe80::/10
    if (/^fe[89ab][0-9a-f]:/.test(h)) return true;

    return false;
}

/**
 * 主机名是否是内部地址。
 *
 * @param hostname 主机名或 IP 字面量 (不含端口)
 */
export function isInternalHost(hostname: string): boolean {
    const h = hostname.trim().toLowerCase();
    if (!h) return true;

    if (BLOCKED_HOSTNAMES.has(h)) return true;
    // .local / .internal / .home.arpa 等内部域名
    if (/\.(local|internal|localdomain|home\.arpa)$/.test(h)) return true;

    // 去掉 IPv6 的方括号
    const bare = h.startsWith('[') && h.endsWith(']') ? h.slice(1, -1) : h;

    if (bare.includes(':')) return isPrivateIPv6(bare);
    if (/^\d{1,3}(\.\d{1,3}){3}$/.test(bare)) return isPrivateIPv4(bare);

    return false;
}

export interface ProxyTargetCheck {
    ok: boolean;
    reason?: string;
}

/**
 * 校验代理目标地址。
 *
 * @param rawUrl 用户传入的 url 参数
 */
export function checkProxyTarget(rawUrl: string): ProxyTargetCheck {
    if (!rawUrl || !rawUrl.trim()) {
        return { ok: false, reason: 'Missing URL' };
    }

    let parsed: URL;
    try {
        parsed = new URL(rawUrl);
    } catch {
        return { ok: false, reason: 'Invalid URL' };
    }

    if (!ALLOWED_PROTOCOLS.has(parsed.protocol)) {
        return { ok: false, reason: `Unsupported protocol: ${parsed.protocol}` };
    }

    if (isInternalHost(parsed.hostname)) {
        return { ok: false, reason: 'Internal address is not allowed' };
    }

    return { ok: true };
}
