-- 대기열을 DB 기준으로 재구축 — DEL + ZADD + SET seq + EXPIREAT 전체를 원자 실행
--
-- KEYS[1]   = 대기열 ZSET (waiting:popup:{id})
-- KEYS[2]   = seq 카운터 (seq:popup:{id})
-- ARGV[1]   = MAX seq (ADMITTED 포함 전체 최댓값)
-- ARGV[2]   = EXPIREAT 대상 unix timestamp(초)
-- ARGV[3..] = score, member, score, member, ... (WAITING 행, seq 오름차순)
-- 반환      = ZADD로 실제 추가된 멤버 수
--
-- 왜 Lua인가:
--   executePipelined는 원자적이지 않다(MULTI/EXEC이 아니라 단순 배치 전송). 그래서 DEL이 나간 뒤
--   ZADD가 다 도착하기 전에 다른 커넥션의 enqueue가 끼면, 그 대기자는 DEL로 지워지고 ZADD 목록에도
--   없으므로 통째로 사라진다. seq 카운터도 DEL~SET 사이에 INCR이 끼면 되돌려져 score가 충돌한다.
--   스크립트는 원자적으로 실행되므로 이 구간이 전부 닫힌다.
--
-- 주의 1 (ZADD 청크):
--   Lua의 unpack()은 LUAI_MAXCSTACK(기본 8000)을 넘는 테이블을 펼치지 못하고
--   "too many results to unpack" 에러가 난다. WAITING이 수천 건이면 한 번에 못 넘기므로
--   500쌍(=1001개 인자)씩 나눠 ZADD를 호출한다. 스크립트 안이라 나눠 불러도 원자성은 유지된다.
--
-- 주의 2 (스크립트 실행 시간):
--   Redis는 싱글 스레드라 스크립트 실행 동안 다른 명령이 전부 블로킹된다. 루프가 WAITING 건수에
--   비례하므로, 대기열이 아주 길어지면(수만 건) 블로킹이 체감될 수 있다. 그때는 재구축을
--   seq 구간별로 쪼개는 설계가 필요하지만, 쪼개는 순간 원자성이 깨지므로 트래픽 차단 게이트와
--   함께 가야 한다.
--
-- 주의 3 (Redis Cluster):
--   KEYS[1]과 KEYS[2]는 prefix가 달라 서로 다른 슬롯에 배치된다. Cluster로 전환하면 CROSSSLOT
--   에러가 나므로 두 키를 같은 해시태그로 묶어야 한다 (예: waiting:{popup:1} / seq:{popup:1}).

local ZADD_CHUNK_PAIRS = 500

redis.call('DEL', KEYS[1], KEYS[2])

local total = #ARGV
local index = 3
local added = 0

while index <= total do
	local zaddArgs = {KEYS[1]}
	local chunkPairs = 0

	while index <= total and chunkPairs < ZADD_CHUNK_PAIRS do
		zaddArgs[#zaddArgs + 1] = ARGV[index]        -- score (seq)
		zaddArgs[#zaddArgs + 1] = ARGV[index + 1]    -- member (userId)
		index = index + 2
		chunkPairs = chunkPairs + 1
	end

	added = added + redis.call('ZADD', unpack(zaddArgs))
end

redis.call('SET', KEYS[2], ARGV[1])

-- enqueue()와 동일한 공식: reservationEndAt + queueTtlBufferSeconds
-- WAITING=0 이면 KEYS[1]이 존재하지 않아 EXPIREAT이 no-op이지만, seq 키에는 반드시 적용돼야 한다
redis.call('EXPIREAT', KEYS[1], ARGV[2])
redis.call('EXPIREAT', KEYS[2], ARGV[2])

return added
