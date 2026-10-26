import { getOptionalRequestContext } from '@cloudflare/next-on-pages';

/**
 * Read an environment value that may live in any of the supported runtimes.
 *
 * Order of precedence:
 *   1) Per-request Cloudflare context env (production Pages/Workers)
 *   2) Standalone config env baked into server.js (Next.js standalone build)
 *   3) OS process.env (Node / Docker)
 *   4) Compile-time fallback
 */
function getStandaloneConfigEnv(name: string): string {
  try {
    const raw = process.env.__NEXT_PRIVATE_STANDALONE_CONFIG;
    if (!raw) return '';
    const cfg = JSON.parse(raw);
    const env = (cfg && cfg.env) || {};
    const value = env[name];
    return typeof value === 'string' ? value : '';
  } catch {
    return '';
  }
}

export function getRuntimeEnvValue(name: string, fallback = ''): string {
  try {
    const runtimeEnv = getOptionalRequestContext()?.env as unknown as Record<string, unknown> | undefined;
    const value = runtimeEnv?.[name];
    if (typeof value === 'string') return value;
  } catch {
    // Outside Cloudflare's request runtime, fall back to standalone config.
  }

  const fromStandalone = getStandaloneConfigEnv(name);
  if (fromStandalone) return fromStandalone;

  if (typeof process !== 'undefined') {
    const fromProcess = process.env[name];
    if (typeof fromProcess === 'string' && fromProcess) return fromProcess;
  }

  return fallback;
}