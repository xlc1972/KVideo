import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

const projectRoot = process.cwd();

test('native player bridge module exposes a safe no-op on browsers', () => {
  const source = readFileSync(
    join(projectRoot, 'lib/utils/native-player-bridge.ts'),
    'utf8',
  );

  // 浏览器里没有 KVideoAndroid, 必须返回 undefined / false, 行为不变
  assert.match(source, /export function getAndroidPlayerBridge\(\): AndroidNativePlayerBridge \| undefined/);
  assert.match(source, /if \(typeof window === 'undefined'\) return undefined;/);
  assert.match(source, /return window\.KVideoAndroid;/);

  // tryNativePlayback 必须在桥不存在时返回 false (交回 WebView)
  assert.match(source, /if \(typeof bridge\?\.playNative !== 'function'\) return false;/);
  assert.match(source, /return bridge\.playNative\(url, title\) === true;/);

  // 桥抛异常时必须 fallback, 不能让播放整个挂掉
  assert.match(source, /catch \(error\) \{[\s\S]*?return false;/);
});

test('hls player hands off to the android native player before setting up hls.js', () => {
  const source = readFileSync(
    join(projectRoot, 'components/player/hooks/useHlsPlayer.ts'),
    'utf8',
  );

  assert.match(
    source,
    /import \{ tryNativePlayback \} from '@\/lib\/utils\/native-player-bridge';/,
  );

  // 原生接管必须发生在创建 Hls 实例之前
  const handoff = source.indexOf('tryNativePlayback(src)');
  const hlsCreate = source.indexOf('new Hls(');
  assert.ok(handoff > -1, 'useHlsPlayer should call tryNativePlayback');
  assert.ok(hlsCreate > -1, 'useHlsPlayer should still create Hls for browsers');
  assert.ok(handoff < hlsCreate, 'native handoff must happen before Hls.js setup');
});

