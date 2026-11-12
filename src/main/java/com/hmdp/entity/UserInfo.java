package com.hmdp.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;

/**
 * @author 虎哥
 * @since 2021-12-24
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@Entity
@Table(name = "tb_user_info")
@DynamicInsert
@DynamicUpdate
public class UserInfo implements Serializable {

  private static final long serialVersionUID = 1L;

  /** 主键，用户id */
  @Id private Long userId;

  /** 城市名称 */
  private String city;

  /** 个人介绍，不要超过128个字符 */
  private String introduce;

  /** 粉丝数量 */
  private Integer fans;

  /** 关注的人的数量 */
  private Integer followee;

  /** 性别，0：男，1：女 */
  private Boolean gender;

  /** 生日 */
  private LocalDate birthday;

  /** 积分 */
  private Integer credits;

  /** 会员级别，0~9级,0代表未开通会员 */
  private Boolean level;

  /** 创建时间 */
  private LocalDateTime createTime;

  /** 更新时间 */
  private LocalDateTime updateTime;
}
