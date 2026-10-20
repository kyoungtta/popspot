import http from 'k6/http';
import { check } from 'k6';

// 실행 예: k6 run -e RATE=300 popup-detail-test.js
// RATE만 바꿔서 300 → 500 → 1000 단계별로 측정
const RATE = Number(__ENV.RATE || 300);
const DURATION = __ENV.DURATION || '60s';
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

// DB에 실제로 있는 팝업 id로 바꿔야 함
const POPUP_IDS = [1, 2, 3, 900000];

export const options = {
    scenarios: {
        popup_detail: {
            executor: 'constant-arrival-rate', // 초당 요청 수를 고정
            rate: RATE,
            timeUnit: '1s',
            duration: DURATION,
            preAllocatedVUs: 200,
            maxVUs: 2000,
        },
    },
    summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
};

export default function () {
    const id = POPUP_IDS[Math.floor(Math.random() * POPUP_IDS.length)];
    const res = http.get(`${BASE_URL}/popups/${id}`);
    check(res, { 'status 200': (r) => r.status === 200 });
}