/**
 * api.js —— 前端与本地服务通信的唯一入口。
 *
 * 约定（见 docs/CONTRACTS.md）：
 *   POST /api/<action>，JSON body；返回 {ok:true,...} 或 {ok:false,error:"中文错误"}。
 *   注意：`devices` 等个别接口历史上不带 ok 字段，因此只有显式 ok:false 才算失败。
 *
 *   api() 会自动把错误以 toast 展示；调用方需要自己处理错误提示时传 {silent:true}。
 */
import { toast } from './ui.js';

export class ApiError extends Error {
  constructor(message, response) {
    super(message || '操作失败');
    this.name = 'ApiError';
    this.response = response || null;
  }
}

const NETWORK_ERROR = '无法连接本地服务，请关闭程序后重新打开。';

/**
 * 调用后端接口。
 * @param {string} action 接口名，如 'devices'
 * @param {object} payload 请求体
 * @param {{silent?:boolean}} [options] silent 时不自动 toast
 * @returns {Promise<object>} 后端 JSON（用户取消时可能 {ok:false,canceled:true}）
 */
export async function api(action, payload = {}, options = {}) {
  const silent = Boolean(options.silent);
  let response;
  try {
    const res = await fetch('/api/' + encodeURIComponent(action), {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload || {}),
    });
    const text = await res.text();
    try {
      response = JSON.parse(text || '{}');
    } catch (parseError) {
      throw new ApiError('本地服务返回的数据无法解析，请重启程序再试。', { status: res.status });
    }
  } catch (error) {
    if (error instanceof ApiError) {
      if (!silent) toast(error.message, 'error');
      throw error;
    }
    const message = NETWORK_ERROR;
    if (!silent) toast(message, 'error');
    throw new ApiError(message);
  }

  if (response && response.ok === false) {
    if (response.canceled) {
      // 用户主动取消系统对话框：不当作错误。
      return response;
    }
    const message = response.error || response.message || '操作失败，请重试。';
    if (!silent) toast(message, 'error');
    throw new ApiError(message, response);
  }
  return response;
}

/** 把图片 URL 加载成 HTMLImageElement。 */
export function loadImage(url) {
  return new Promise((resolve, reject) => {
    if (!url) {
      reject(new Error('图片地址为空'));
      return;
    }
    const image = new Image();
    image.decoding = 'async';
    image.onload = () => resolve(image);
    image.onerror = () => reject(new Error('图片加载失败'));
    image.src = url;
  });
}

/** 截图静态资源地址。 */
export function shotUrl(sid, shotId) {
  return '/media/shot/' + encodeURIComponent(sid) + '/' + encodeURIComponent(shotId) + '.png';
}

/** 视频流地址（供 <video> 元素播放）。 */
export function videoUrl(sid) {
  return '/media/video/' + encodeURIComponent(sid);
}

/** 把 URL（/media/...）读成 dataURL，用于保存原始截图等场景。 */
export async function fetchAsDataUrl(url) {
  const res = await fetch(url);
  if (!res.ok) throw new Error('读取图片失败：' + res.status);
  const blob = await res.blob();
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result || ''));
    reader.onerror = () => reject(new Error('读取图片失败'));
    reader.readAsDataURL(blob);
  });
}

/** 在前端日志中留下记录（不弹 toast，失败也不打断流程）。 */
export function reportLog(message) {
  try {
    fetch('/api/log.write', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ message: String(message || '').slice(0, 4000) }),
    }).catch(() => {});
  } catch (error) {
    /* 忽略 */
  }
}
