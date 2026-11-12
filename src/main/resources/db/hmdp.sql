/*
 HarbourDrop baseline schema and demo seed data (schema version 0).

 MySQL runs this file once when the data volume is empty; Flyway migrations
 V1+ (src/main/resources/db/migration) then evolve it. Keep table structures in
 sync with what those migrations expect.

 All businesses, people and reviews below are fictional. Shop coordinates are
 real Vancouver-area neighbourhoods (x = longitude, y = latitude).
*/

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ----------------------------
-- Table structure for tb_blog
-- ----------------------------
DROP TABLE IF EXISTS `tb_blog`;
CREATE TABLE `tb_blog`  (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
  `shop_id` bigint(20) NOT NULL COMMENT 'Shop id',
  `user_id` bigint(20) UNSIGNED NOT NULL COMMENT 'Author user id',
  `title` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'Title',
  `images` varchar(2048) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT 'Up to 9 photos, comma separated',
  `content` varchar(2048) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'Review text',
  `liked` int(8) UNSIGNED NULL DEFAULT 00000000 COMMENT 'Like count',
  `comments` int(8) UNSIGNED NULL DEFAULT NULL COMMENT 'Comment count',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Created at',
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Updated at',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 8 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = Compact;

-- ----------------------------
-- Records of tb_blog
-- ----------------------------
INSERT INTO `tb_blog` VALUES (4, 1, 2, 'A $6 surprise bag from Tidewater Bakehouse was the best deal of my week', '/assets/shops/bag.svg,/assets/shops/bakery.svg', 'Grabbed the 5:30 PM drop on my way home through Gastown. Pickup was a ten-minute window right before closing and the staff had the bags ready at the counter.\n\nInside: two almond croissants, half a sourdough loaf, a cardamom bun and a slightly squashed cinnamon knot. Easily $20 of pastry for $6, and all of it would have been thrown out an hour later.\n\nTip: the drops go fast. Follow the shop so the new drop shows up in your feed.', 00000013, 00000004, '2026-10-18 01:50:01', '2026-10-18 01:50:01');
INSERT INTO `tb_blog` VALUES (5, 7, 2, 'Golden Lantern: weekend dim sum worth the wait in Chinatown', '/assets/shops/dimsum.svg', 'Came for Saturday dim sum with four friends. The har gow wrappers were thin and the shrimp snapped, the siu mai were generous and the baked BBQ pork buns disappeared first.\n\nWe waited about 20 minutes at 11 AM; earlier is quieter. Around $28 per person with tea.\n\nThey also post an evening surplus bag with whatever buns and dumplings are left. Worth trying for next-day lunch.', 00000009, 00000002, '2026-10-25 19:12:44', '2026-10-25 19:12:44');
INSERT INTO `tb_blog` VALUES (6, 15, 1, 'End-of-day deli bag from Granville Island Provisions', '/assets/shops/deli.svg,/assets/shops/bag.svg', 'Picked up a deli surprise bag after a walk around the island: a turkey and brie sandwich, a tub of marinated olives, a wedge of aged cheddar and two ripe pears.\n\nThe pickup window was 6:30 to 7:00 PM and it was ready under my name. Great value for a picnic at Vanier Park the next day.', 00000011, 00000001, '2026-11-02 02:05:47', '2026-11-02 02:05:47');
INSERT INTO `tb_blog` VALUES (7, 11, 1, 'Seawall Brunch Co. after a morning run on English Bay', '/assets/shops/brunch.svg', 'Crab cake benny with crispy potatoes and a proper flat white, with a view of the water. Service was quick even on a Sunday.\n\nUsed the weekday brunch credit deal and saved about $9 for two. Book ahead on weekends.', 00000007, 00000000, '2026-11-09 18:30:12', '2026-11-09 18:30:12');

-- ----------------------------
-- Table structure for tb_blog_comments
-- ----------------------------
DROP TABLE IF EXISTS `tb_blog_comments`;
CREATE TABLE `tb_blog_comments`  (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
  `user_id` bigint(20) UNSIGNED NOT NULL COMMENT 'User id',
  `blog_id` bigint(20) UNSIGNED NOT NULL COMMENT 'Review id',
  `parent_id` bigint(20) UNSIGNED NOT NULL COMMENT 'Top-level comment id; 0 for a top-level comment',
  `answer_id` bigint(20) UNSIGNED NOT NULL COMMENT 'Id of the comment being replied to',
  `content` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT 'Reply text',
  `liked` int(8) UNSIGNED NULL DEFAULT NULL COMMENT 'Like count',
  `status` tinyint(1) UNSIGNED NULL DEFAULT NULL COMMENT 'Status: 0 normal, 1 reported, 2 hidden',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Created at',
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Updated at',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = Compact;

-- ----------------------------
-- Records of tb_blog_comments
-- ----------------------------

-- ----------------------------
-- Table structure for tb_follow
-- ----------------------------
DROP TABLE IF EXISTS `tb_follow`;
CREATE TABLE `tb_follow`  (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
  `user_id` bigint(20) UNSIGNED NOT NULL COMMENT 'Follower user id',
  `follow_user_id` bigint(20) UNSIGNED NOT NULL COMMENT 'Followed user id',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Created at',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = Compact;

-- ----------------------------
-- Records of tb_follow
-- ----------------------------

-- ----------------------------
-- Table structure for tb_seckill_voucher
-- ----------------------------
DROP TABLE IF EXISTS `tb_seckill_voucher`;
CREATE TABLE `tb_seckill_voucher`  (
  `voucher_id` bigint(20) UNSIGNED NOT NULL COMMENT 'Voucher id (one-to-one with tb_voucher)',
  `stock` int(8) NOT NULL COMMENT 'Remaining stock',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Created at',
  `begin_time` timestamp NOT NULL DEFAULT '0000-00-00 00:00:00' COMMENT 'Sale opens at',
  `end_time` timestamp NOT NULL DEFAULT '0000-00-00 00:00:00' COMMENT 'Sale closes at',
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Updated at',
  PRIMARY KEY (`voucher_id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = 'Limited drops (flash-sale vouchers), one-to-one with tb_voucher' ROW_FORMAT = Compact;

-- ----------------------------
-- Records of tb_seckill_voucher
-- ----------------------------

-- ----------------------------
-- Table structure for tb_shop
-- ----------------------------
DROP TABLE IF EXISTS `tb_shop`;
CREATE TABLE `tb_shop`  (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
  `name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT 'Shop name',
  `type_id` bigint(20) UNSIGNED NOT NULL COMMENT 'Shop type id',
  `images` varchar(1024) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT 'Shop images, comma separated',
  `area` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT 'Neighbourhood, e.g. Gastown',
  `address` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT 'Street address',
  `x` double NOT NULL COMMENT 'Longitude',
  `y` double NOT NULL COMMENT 'Latitude',
  `avg_price` bigint(10) UNSIGNED NULL DEFAULT NULL COMMENT 'Average spend per person, whole dollars',
  `sold` int(10) UNSIGNED NOT NULL COMMENT 'Units sold',
  `comments` int(10) UNSIGNED NOT NULL COMMENT 'Review count',
  `score` int(2) UNSIGNED NOT NULL COMMENT 'Rating 1-5, stored x10 to avoid decimals',
  `open_hours` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT 'Opening hours, e.g. 10:00-22:00',
  `create_time` timestamp NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Created at',
  `update_time` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Updated at',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `foreign_key_type`(`type_id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 17 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = Compact;

-- ----------------------------
-- Records of tb_shop
-- ----------------------------
INSERT INTO `tb_shop` VALUES (1, 'Tidewater Bakehouse', 1, '/assets/shops/bakery.svg,/assets/shops/bag.svg', 'Gastown', '212 Carrall St, Vancouver', -123.1043, 49.2836, 14, 0000004215, 0000000812, 47, '07:00-18:00', '2026-09-01 16:10:39', '2026-11-01 17:32:19');
INSERT INTO `tb_shop` VALUES (2, 'Kits Crumb & Co.', 1, '/assets/shops/bakery.svg', 'Kitsilano', '2185 W 4th Ave, Vancouver', -123.1580, 49.2683, 12, 0000003120, 0000000640, 46, '07:30-17:30', '2026-09-01 16:12:10', '2026-11-01 17:32:19');
INSERT INTO `tb_shop` VALUES (3, 'Northshore Roast', 2, '/assets/shops/cafe.svg', 'Lonsdale Quay', '128 Esplanade W, North Vancouver', -123.0815, 49.3104, 9, 0000005208, 0000000930, 45, '06:30-19:00', '2026-09-01 16:15:05', '2026-11-01 17:32:19');
INSERT INTO `tb_shop` VALUES (4, 'Main Street Grind', 2, '/assets/shops/cafe.svg', 'Mount Pleasant', '2412 Main St, Vancouver', -123.1007, 49.2638, 8, 0000006011, 0000001104, 46, '07:00-20:00', '2026-09-01 16:17:15', '2026-11-01 17:32:19');
INSERT INTO `tb_shop` VALUES (5, 'Harbour Light Sushi', 3, '/assets/shops/sushi.svg', 'Coal Harbour', '575 Bute St, Vancouver', -123.1225, 49.2895, 32, 0000003519, 0000000729, 47, '11:30-21:30', '2026-09-01 16:20:58', '2026-11-01 17:32:19');
INSERT INTO `tb_shop` VALUES (6, 'Robson Nori Bar', 3, '/assets/shops/sushi.svg', 'West End', '1688 Robson St, Vancouver', -123.1345, 49.2905, 26, 0000004125, 0000000764, 45, '11:00-22:00', '2026-09-01 16:24:53', '2026-11-01 17:32:19');
INSERT INTO `tb_shop` VALUES (7, 'Golden Lantern Dim Sum', 4, '/assets/shops/dimsum.svg,/assets/shops/bag.svg', 'Chinatown', '268 Keefer St, Vancouver', -123.0990, 49.2794, 28, 0000009531, 0000001324, 46, '09:00-21:00', '2026-09-01 16:40:52', '2026-11-01 17:32:19');
INSERT INTO `tb_shop` VALUES (8, 'Aberdeen Steamer House', 4, '/assets/shops/dimsum.svg', 'Richmond', '8391 Alexandra Rd, Richmond', -123.1335, 49.1840, 30, 0000002406, 0000000606, 47, '10:00-22:00', '2026-09-01 16:51:06', '2026-11-01 17:32:19');
INSERT INTO `tb_shop` VALUES (9, 'Commercial Drive Slice', 5, '/assets/shops/pizza.svg', 'Commercial Drive', '1520 Commercial Dr, Vancouver', -123.0695, 49.2700, 20, 0000002763, 0000000563, 45, '11:30-23:00', '2026-09-01 16:53:59', '2026-11-01 17:32:19');
INSERT INTO `tb_shop` VALUES (10, 'Yaletown Forno', 5, '/assets/shops/pizza.svg', 'Yaletown', '1130 Mainland St, Vancouver', -123.1205, 49.2756, 34, 0000002891, 0000000502, 46, '12:00-22:30', '2026-09-01 17:25:16', '2026-11-01 17:32:19');
INSERT INTO `tb_shop` VALUES (11, 'Seawall Brunch Co.', 6, '/assets/shops/brunch.svg', 'English Bay', '1750 Beach Ave, Vancouver', -123.1415, 49.2862, 25, 0000005977, 0000000884, 45, '08:00-15:00', '2026-09-01 17:29:02', '2026-11-01 17:32:19');
INSERT INTO `tb_shop` VALUES (12, 'Kerrisdale Morning Table', 6, '/assets/shops/brunch.svg', 'Kerrisdale', '2210 W 41st Ave, Vancouver', -123.1560, 49.2342, 22, 0000001444, 0000000235, 44, '08:00-14:30', '2026-09-01 17:34:34', '2026-11-01 17:32:19');
INSERT INTO `tb_shop` VALUES (13, 'Greenroot Kitchen', 7, '/assets/shops/plant.svg,/assets/shops/bag.svg', 'Mount Pleasant', '18 E Broadway, Vancouver', -123.1015, 49.2630, 18, 0000003997, 0000000657, 46, '11:00-21:00', '2026-09-01 17:38:54', '2026-11-01 17:32:19');
INSERT INTO `tb_shop` VALUES (14, 'False Creek Grain Bowl', 7, '/assets/shops/plant.svg', 'Olympic Village', '1755 Manitoba St, Vancouver', -123.1080, 49.2690, 16, 0000001771, 0000000385, 45, '10:30-20:00', '2026-09-01 17:48:54', '2026-11-01 17:32:19');
INSERT INTO `tb_shop` VALUES (15, 'Granville Island Provisions', 8, '/assets/shops/deli.svg,/assets/shops/bag.svg', 'Granville Island', '1585 Johnston St, Vancouver', -123.1340, 49.2712, 15, 0000004890, 0000000911, 47, '09:00-19:00', '2026-09-01 17:52:20', '2026-11-01 17:32:19');
INSERT INTO `tb_shop` VALUES (16, 'Metrotown Corner Deli', 8, '/assets/shops/deli.svg', 'Burnaby', '6010 Kingsway, Burnaby', -122.9990, 49.2255, 13, 0000002210, 0000000318, 44, '08:00-20:00', '2026-09-01 17:55:41', '2026-11-01 17:32:19');

-- ----------------------------
-- Table structure for tb_shop_type
-- ----------------------------
DROP TABLE IF EXISTS `tb_shop_type`;
CREATE TABLE `tb_shop_type`  (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
  `name` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT 'Type name',
  `icon` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT 'Icon',
  `sort` int(3) UNSIGNED NULL DEFAULT NULL COMMENT 'Display order',
  `create_time` timestamp NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Created at',
  `update_time` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Updated at',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 9 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = Compact;

-- ----------------------------
-- Records of tb_shop_type
-- ----------------------------
INSERT INTO `tb_shop_type` VALUES (1, 'Bakeries', '/assets/shops/bakery.svg', 1, '2026-09-01 16:00:00', '2026-09-01 16:00:00');
INSERT INTO `tb_shop_type` VALUES (2, 'Cafés', '/assets/shops/cafe.svg', 2, '2026-09-01 16:00:00', '2026-09-01 16:00:00');
INSERT INTO `tb_shop_type` VALUES (3, 'Sushi & Japanese', '/assets/shops/sushi.svg', 3, '2026-09-01 16:00:00', '2026-09-01 16:00:00');
INSERT INTO `tb_shop_type` VALUES (4, 'Dim Sum & Chinese', '/assets/shops/dimsum.svg', 4, '2026-09-01 16:00:00', '2026-09-01 16:00:00');
INSERT INTO `tb_shop_type` VALUES (5, 'Pizza & Italian', '/assets/shops/pizza.svg', 5, '2026-09-01 16:00:00', '2026-09-01 16:00:00');
INSERT INTO `tb_shop_type` VALUES (6, 'Brunch', '/assets/shops/brunch.svg', 6, '2026-09-01 16:00:00', '2026-09-01 16:00:00');
INSERT INTO `tb_shop_type` VALUES (7, 'Plant-based', '/assets/shops/plant.svg', 7, '2026-09-01 16:00:00', '2026-09-01 16:00:00');
INSERT INTO `tb_shop_type` VALUES (8, 'Grocery & Deli', '/assets/shops/deli.svg', 8, '2026-09-01 16:00:00', '2026-09-01 16:00:00');

-- ----------------------------
-- Table structure for tb_user
-- ----------------------------
DROP TABLE IF EXISTS `tb_user`;
CREATE TABLE `tb_user`  (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
  `phone` varchar(11) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT 'Phone number (legacy; sign-in uses email since V10)',
  `password` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT '' COMMENT 'Password hash (unused)',
  `nick_name` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT '' COMMENT 'Display name',
  `icon` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT '' COMMENT 'Avatar',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Created at',
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Updated at',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uniqe_key_phone`(`phone`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1010 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = Compact;

-- ----------------------------
-- Records of tb_user
-- (V10 assigns emails user<id>@example.com; 555-01xx numbers are reserved for fiction.)
-- ----------------------------
INSERT INTO `tb_user` VALUES (1, '6045550101', '', 'Maya on the Drive', '', '2026-09-10 17:27:19', '2026-09-10 17:27:19');
INSERT INTO `tb_user` VALUES (2, '6045550102', '', 'Kits Foodie', '', '2026-09-10 22:14:39', '2026-09-10 22:14:39');
INSERT INTO `tb_user` VALUES (4, '6045550104', '', 'Jordan L.', '', '2026-09-12 19:07:53', '2026-09-12 19:07:53');
INSERT INTO `tb_user` VALUES (5, '6045550105', '', 'Sam T.', '', '2026-09-12 23:11:33', '2026-09-12 23:11:33');

-- ----------------------------
-- Table structure for tb_user_info
-- ----------------------------
DROP TABLE IF EXISTS `tb_user_info`;
CREATE TABLE `tb_user_info`  (
  `user_id` bigint(20) UNSIGNED NOT NULL COMMENT 'Primary key, user id',
  `city` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT '' COMMENT 'City',
  `introduce` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT 'Short bio, at most 128 characters',
  `fans` int(8) UNSIGNED NULL DEFAULT 0 COMMENT 'Follower count',
  `followee` int(8) UNSIGNED NULL DEFAULT 0 COMMENT 'Following count',
  `gender` tinyint(1) UNSIGNED NULL DEFAULT 0 COMMENT 'Gender code (unused by the UI)',
  `birthday` date NULL DEFAULT NULL COMMENT 'Birthday',
  `credits` int(8) UNSIGNED NULL DEFAULT 0 COMMENT 'Reward points',
  `level` tinyint(1) UNSIGNED NULL DEFAULT 0 COMMENT 'Membership level 0-9; 0 means none',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Created at',
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Updated at',
  PRIMARY KEY (`user_id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = Compact;

-- ----------------------------
-- Records of tb_user_info
-- ----------------------------

-- ----------------------------
-- Table structure for tb_voucher
-- ----------------------------
DROP TABLE IF EXISTS `tb_voucher`;
CREATE TABLE `tb_voucher`  (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
  `shop_id` bigint(20) UNSIGNED NULL DEFAULT NULL COMMENT 'Shop id',
  `title` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT 'Title',
  `sub_title` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT 'Subtitle',
  `rules` varchar(1024) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT 'Terms; lines separated by a literal \\n',
  `pay_value` bigint(10) UNSIGNED NOT NULL COMMENT 'Price paid, in cents (200 = $2.00)',
  `actual_value` bigint(10) NOT NULL COMMENT 'Value received, in cents (200 = $2.00)',
  `type` tinyint(1) UNSIGNED NOT NULL DEFAULT 0 COMMENT '0 = regular deal; 1 = limited drop (flash sale)',
  `status` tinyint(1) UNSIGNED NOT NULL DEFAULT 1 COMMENT '1 = listed; 2 = unlisted; 3 = expired',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Created at',
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Updated at',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 10 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = Compact;

-- ----------------------------
-- Records of tb_voucher (regular deals; limited drops are created at runtime)
-- ----------------------------
INSERT INTO `tb_voucher` VALUES (1, 1, '$10 bakery credit', 'Pay $8, spend $10', 'Valid every day\\nDine-in or takeout\\nOne credit per visit\\nNo cash value, no change given', 800, 1000, 0, 1, '2026-09-15 16:42:39', '2026-09-15 16:42:39');
INSERT INTO `tb_voucher` VALUES (2, 4, 'Coffee & pastry combo', 'Any coffee plus a pastry', 'Valid every day before 11 AM\\nDine-in or takeout\\nNo cash value', 650, 900, 0, 1, '2026-09-15 16:44:02', '2026-09-15 16:44:02');
INSERT INTO `tb_voucher` VALUES (3, 7, '$20 dim sum credit', 'Pay $17, spend $20', 'Valid Monday to Friday\\nDine-in only\\nNot valid on statutory holidays\\nNo cash value', 1700, 2000, 0, 1, '2026-09-15 16:45:31', '2026-09-15 16:45:31');
INSERT INTO `tb_voucher` VALUES (4, 9, 'Happy hour slice deal', 'Two slices and a drink, Mon-Thu 3-6 PM', 'Valid Monday to Thursday, 3-6 PM\\nDine-in only\\nNo cash value', 1200, 1600, 0, 1, '2026-09-15 16:47:12', '2026-09-15 16:47:12');
INSERT INTO `tb_voucher` VALUES (5, 11, 'Weekday brunch credit for two', 'Pay $36, spend $45', 'Valid Monday to Friday\\nReservations recommended\\nNo cash value', 3600, 4500, 0, 1, '2026-09-15 16:48:55', '2026-09-15 16:48:55');

-- ----------------------------
-- Table structure for tb_voucher_order
-- ----------------------------
DROP TABLE IF EXISTS `tb_voucher_order`;
CREATE TABLE `tb_voucher_order`  (
  `id` bigint(20) NOT NULL COMMENT 'Primary key',
  `user_id` bigint(20) UNSIGNED NOT NULL COMMENT 'Buyer user id',
  `voucher_id` bigint(20) UNSIGNED NOT NULL COMMENT 'Voucher id',
  `pay_type` tinyint(1) UNSIGNED NOT NULL DEFAULT 1 COMMENT 'Payment method code (simulated)',
  `status` tinyint(1) UNSIGNED NOT NULL DEFAULT 1 COMMENT 'Status: 1 unpaid, 2 paid, 3 redeemed, 4 cancelled, 5 refunding, 6 refunded',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Ordered at',
  `pay_time` timestamp NULL DEFAULT NULL COMMENT 'Paid at',
  `use_time` timestamp NULL DEFAULT NULL COMMENT 'Redeemed at',
  `refund_time` timestamp NULL DEFAULT NULL COMMENT 'Refunded at',
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Updated at',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = Compact;

-- ----------------------------
-- Records of tb_voucher_order
-- ----------------------------

SET FOREIGN_KEY_CHECKS = 1;
