-- 재구축 중이 아닐 때만 잔여 정원을 차감한다.
--
-- KEYS[1] = 잔여 정원 카운터 (popspot:reservation:slot:{id}:remaining)
-- KEYS[2] = 재구축 게이트 (rebuild:reservation:slot:{id})
-- 반환    = {0}            → 재구축 중, 차감하지 않음
--           {1, remaining} → 차감 성공, remaining은 DECR 결과
--
-- 게이트를 EXISTS로 따로 확인한 뒤 DECR을 치면 그 사이에 재구축이 시작될 수 있고, 그 DECR은
-- 뒤따르는 blind write에 덮어써진다. 검사와 차감을 한 스크립트에 묶어 창을 없앤다.
-- 왕복도 늘지 않는다 (EXISTS + DECR 2회 → eval 1회).
--
-- 반환을 배열로 두는 이유: 단일 정수로 "재구축 중"을 표현하려면 sentinel 값이 필요한데,
-- DECR 결과는 초과 예약이 몰리면 음수로 내려갈 수 있어 어떤 값을 골라도 충돌 가능성이 남는다.
--
-- 주의(Redis Cluster): KEYS[1]과 KEYS[2]는 prefix가 달라 서로 다른 슬롯에 배치된다.
-- Cluster로 전환하면 CROSSSLOT 에러가 나므로, 두 키를 같은 해시태그로 묶어야 한다
-- (예: popspot:{slot:123}:remaining / rebuild:{slot:123}).

if redis.call('EXISTS', KEYS[2]) == 1 then
	return {0}
end

return {1, redis.call('DECR', KEYS[1])}
