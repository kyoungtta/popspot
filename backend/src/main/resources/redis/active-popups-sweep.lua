-- 활성 대기열 팝업 인덱스(Set) 스윕 — SMEMBERS + EXISTS + SREM 원자 실행
--
-- KEYS[1] = active:waiting:popups (활성 팝업 인덱스 Set)
-- ARGV[1] = 대기열 ZSET 키 prefix ("waiting:popup:")
-- 반환    = ZSET이 살아있는 popupId 문자열 배열
--
-- 왜 Lua인가:
--   EXISTS(false) 확인과 SREM 사이에 enqueue(ZADD + SADD)가 끼면, 방금 대기열에 들어온
--   팝업이 인덱스에서 지워진다. 그 팝업에 신규 enqueue가 더 없으면 인덱스를 되살릴 트리거가
--   없으므로 스케줄러가 영원히 admitBatch 대상에서 제외 → 대기자 전원이 멈춘다.
--   Redis는 스크립트를 원자적으로 실행하므로 이 창이 사라진다. 덤으로 popupId 개수(N)만큼
--   나가던 EXISTS 라운드트립이 1회로 줄어든다.
--
-- 주의 1 (Redis Cluster):
--   대기열 ZSET 키를 KEYS가 아니라 스크립트 안에서 prefix로 조립한다. 단일 Redis 기준이며
--   Cluster로 전환하면 선언되지 않은 키 접근 → CROSSSLOT 에러가 난다. 그때는 popupId 1건씩
--   KEYS[1]=인덱스 Set, KEYS[2]=대기열 ZSET 형태로 호출하도록 바꿔야 한다
--   (원자성은 popupId 단위로만 보장되지만, 위 race를 막기에는 그것으로 충분하다).
--
-- 주의 2 (스크립트 실행 시간):
--   Redis는 싱글 스레드라 스크립트 실행 동안 다른 명령이 전부 블로킹된다. 이 루프는 활성
--   팝업 수 N에 비례하므로 현재 규모(N이 작음)에서는 무시 가능하다. N이 커지면 SSCAN 기반
--   커서로 나눠 돌리거나, 인덱스를 여러 Set으로 샤딩해 배치 분할 호출로 바꿔야 한다.

local ids = redis.call('SMEMBERS', KEYS[1])
local alive = {}

for _, id in ipairs(ids) do
	if redis.call('EXISTS', ARGV[1] .. id) == 1 then
		alive[#alive + 1] = id                  -- ZSET 살아있음 → 활성 대기열
	else
		redis.call('SREM', KEYS[1], id)         -- 대기열 소진/TTL 만료 → 인덱스에서 청소
	end
end

return alive
