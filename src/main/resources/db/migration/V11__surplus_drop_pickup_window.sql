-- Limited drops (surplus-food bags) are collected in store during a short pickup window.
-- Both columns are optional so a drop without a pickup window stays valid.
ALTER TABLE tb_seckill_voucher
  ADD COLUMN pickup_begin_time timestamp NULL DEFAULT NULL COMMENT 'Pickup window opens at' AFTER end_time,
  ADD COLUMN pickup_end_time timestamp NULL DEFAULT NULL COMMENT 'Pickup window closes at' AFTER pickup_begin_time;

-- Shop coordinates were UNSIGNED, which cannot hold western longitudes such as Vancouver's.
ALTER TABLE tb_shop
  MODIFY COLUMN x double NOT NULL COMMENT 'Longitude',
  MODIFY COLUMN y double NOT NULL COMMENT 'Latitude';
