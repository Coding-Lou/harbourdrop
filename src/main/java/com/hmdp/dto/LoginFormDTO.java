package com.hmdp.dto;

import lombok.Data;

/** Email one-time-code login. */
@Data
public class LoginFormDTO {
  private String email;
  private String code;
}
