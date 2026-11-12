package com.hmdp.dto;

import com.hmdp.entity.User;
import java.util.HashMap;
import java.util.Map;
import lombok.Data;

@Data
public class UserDTO {
  private Long id;
  private String nickName;
  private String icon;

  /** Public profile fields only; never exposes phone, email or password columns. */
  public static UserDTO from(User user) {
    if (user == null) {
      return null;
    }
    UserDTO dto = new UserDTO();
    dto.setId(user.getId());
    dto.setNickName(user.getNickName());
    dto.setIcon(user.getIcon());
    return dto;
  }

  /** String-valued Redis hash; null fields are omitted. */
  public Map<String, String> toRedisHash() {
    Map<String, String> hash = new HashMap<>();
    if (id != null) {
      hash.put("id", id.toString());
    }
    if (nickName != null) {
      hash.put("nickName", nickName);
    }
    if (icon != null) {
      hash.put("icon", icon);
    }
    return hash;
  }

  public static UserDTO fromRedisHash(Map<?, ?> hash) {
    UserDTO dto = new UserDTO();
    Object id = hash.get("id");
    dto.setId(id == null ? null : Long.valueOf(id.toString()));
    Object nickName = hash.get("nickName");
    dto.setNickName(nickName == null ? null : nickName.toString());
    Object icon = hash.get("icon");
    dto.setIcon(icon == null ? null : icon.toString());
    return dto;
  }
}
