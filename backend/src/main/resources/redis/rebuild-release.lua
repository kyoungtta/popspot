-- 재구축 게이트 해제 — 내 토큰일 때만 DEL
--
-- KEYS[1] = 게이트 키
-- ARGV[1] = 임대 토큰
-- 반환    = 1(해제됨) / 0(내 게이트가 아니거나 이미 만료됨)
--
-- GET으로 소유를 확인하고 DEL 하는 것은 check-then-act라, 그 사이 TTL이 만료되고 다른
-- 인스턴스가 게이트를 새로 잡으면 남의 게이트를 내려버린다. Lua로 묶어 그 창을 없앤다.

if redis.call('GET', KEYS[1]) == ARGV[1] then
	return redis.call('DEL', KEYS[1])
end

return 0
