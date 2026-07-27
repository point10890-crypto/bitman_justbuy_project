-- 종가매매(JONGGA_V2) 추천종목 히스토리: 익일 고가 기준 최대수익률 추적
-- 컬럼명은 Hibernate 기본 네이밍(CamelCaseToUnderscores)을 따른다.
-- highPrice1d -> high_price1d (숫자 앞에는 언더스코어가 붙지 않음. 기존 price1d/return1d 와 동일)
ALTER TABLE IF EXISTS analysis_track_records
    ADD COLUMN IF NOT EXISTS high_price1d BIGINT;

ALTER TABLE IF EXISTS analysis_track_records
    ADD COLUMN IF NOT EXISTS max_return1d DOUBLE PRECISION;
