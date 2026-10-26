'use client';

/**
 * Android TV 原生播放器桥。
 *
 * Android TV 壳 (com.djjk.ziqidonglai.tv) 在 WebView 里注入了 `KVideoAndroid` 对象。
 * 当播放地址是原生播放器能直接吃的 (HLS / mp4), 站点就把它交给 App 的
 * Media3 播放器 —— 老电视的 WebView 用 MSE 播 HLS 常常黑屏, 走系统
 * MediaCodec 兼容性明显更好。
 *
 * 浏览器里没有这个对象, 所有函数都返回 false / null, 行为完全不变。
 */

export interface AndroidNativePlayerBridge {
    /**
     * 请求 App 用原生播放器播放。
     * @returns true = 已交给原生播放器; false = 请继续用 WebView 播
     */
    playNative(url: string, title: string): boolean;
    isPictureInPictureSupported?(): boolean;
    enterPictureInPicture?(
        width: number,
        height: number,
        left: number,
        top: number,
        right: number,
        bottom: number
    ): boolean;
}

declare global {
    interface Window {
        KVideoAndroid?: AndroidNativePlayerBridge;
    }
}

/** 取原生桥 (浏览器里返回 undefined) */
export function getAndroidPlayerBridge(): AndroidNativePlayerBridge | undefined {
    if (typeof window === 'undefined') return undefined;
    return window.KVideoAndroid;
}

/** App 是否提供了原生播放器 */
export function hasNativePlayer(): boolean {
    const bridge = getAndroidPlayerBridge();
    return typeof bridge?.playNative === 'function';
}

/**
 * 尝试用原生播放器播。
 *
 * @param url 播放地址
 * @param title 片名 (给原生播放器显示)
 * @returns true = 已被原生播放器接管; false = 继续用 WebView
 */
export function tryNativePlayback(url: string, title = ''): boolean {
    if (!url) return false;
    const bridge = getAndroidPlayerBridge();
    if (typeof bridge?.playNative !== 'function') return false;
    try {
        return bridge.playNative(url, title) === true;
    } catch (error) {
        console.warn('[NativePlayer] playNative failed, falling back to WebView', error);
        return false;
    }
}
