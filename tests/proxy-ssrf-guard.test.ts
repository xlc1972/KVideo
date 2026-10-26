import test from 'node:test';
import assert from 'node:assert/strict';

import {
  checkProxyTarget,
  isInternalHost,
  isPrivateIPv4,
  isPrivateIPv6,
} from '../lib/utils/proxy-ssrf-guard';

test('private ipv4 ranges are blocked', () => {
  for (const ip of [
    '127.0.0.1',
    '127.1.2.3',
    '10.0.0.1',
    '10.255.255.255',
    '172.16.0.1',
    '172.31.255.255',
    '192.168.1.1',
    '169.254.169.254',
    '0.0.0.0',
    '100.64.0.1',
  ]) {
    assert.equal(isPrivateIPv4(ip), true, ip + ' should be private');
  }
});

test('public ipv4 is allowed', () => {
  for (const ip of ['8.8.8.8', '1.1.1.1', '172.32.0.1', '172.15.0.1', '100.63.255.255', '223.5.5.5']) {
    assert.equal(isPrivateIPv4(ip), false, ip + ' should be public');
  }
});

test('private ipv6 is blocked', () => {
  assert.equal(isPrivateIPv6('::1'), true);
  assert.equal(isPrivateIPv6('::'), true);
  assert.equal(isPrivateIPv6('fd00::1'), true);
  assert.equal(isPrivateIPv6('fe80::1'), true);
  assert.equal(isPrivateIPv6('::ffff:127.0.0.1'), true);
});

test('internal hostnames are blocked', () => {
  for (const h of [
    'localhost',
    'LOCALHOST',
    '0.0.0.0',
    '::1',
    '[::1]',
    '127.0.0.1',
    'foo.local',
    'bar.internal',
    'db.home.arpa',
  ]) {
    assert.equal(isInternalHost(h), true, h + ' should be internal');
  }
});

test('public hostnames are allowed', () => {
  for (const h of [
    'kvideo.djjk.cc',
    'vip.ffzy-play.com',
    'collect.wolongzyw.com',
    '8.8.8.8',
    '[2606:4700:4700::1111]',
  ]) {
    assert.equal(isInternalHost(h), false, h + ' should be public');
  }
});

test('proxy target check rejects internal and non-http targets', () => {
  assert.equal(checkProxyTarget('http://127.0.0.1:13080/family-sources.json').ok, false);
  assert.equal(checkProxyTarget('http://169.254.169.254/latest/meta-data/').ok, false);
  assert.equal(checkProxyTarget('http://192.168.0.1/admin').ok, false);
  assert.equal(checkProxyTarget('file:///etc/passwd').ok, false);
  assert.equal(checkProxyTarget('ftp://example.com/a').ok, false);
  assert.equal(checkProxyTarget('gopher://example.com/a').ok, false);
  assert.equal(checkProxyTarget('/family-sources.json').ok, false);
  assert.equal(checkProxyTarget('').ok, false);
  assert.equal(checkProxyTarget('not a url').ok, false);
});

test('proxy target check allows real media sources', () => {
  assert.equal(
    checkProxyTarget('https://vip.ffzy-play.com/20221106/14125_9f441549/index.m3u8').ok,
    true,
  );
  assert.equal(
    checkProxyTarget('http://106.15.153.201/ziqidonglai/latest.apk').ok,
    true,
  );
});
