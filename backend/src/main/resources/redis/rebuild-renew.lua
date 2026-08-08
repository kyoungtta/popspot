-- 재구축 게이트 TTL 연장 — 내 토큰일 때만 EXPIRE
--
-- KEYS[1] = 게이트 키
-- ARGV[1] = 임대 토큰
-- ARGV[2] = 연장할 TTL(초)
-- 반환    = 1(연장됨) / 0(내 게이트가 아니거나 이미 만료됨)

if redis.call('GET', KEYS[1]) == ARGV[1] then
	return redis.call('EXPIRE', KEYS[1], ARGV[2])
end

return 0
