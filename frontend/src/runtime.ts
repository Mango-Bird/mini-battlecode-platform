export const safeStaging = import.meta.env.VITE_RUNTIME_MODE === 'SAFE_STAGING_WEB_ONLY';
export const playerToolsEnabled = import.meta.env.DEV || safeStaging;
export const executionUnavailable = 'Bot execution, Ranked search và Tournament execution chưa được bật trên staging. Bạn có thể sửa/lưu bot và xem lịch sử có sẵn.';
