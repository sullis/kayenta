/*
 * Copyright 2024 Google, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.kayenta.aws2.security;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.netflix.kayenta.security.AccountCredentials;
import javax.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import software.amazon.awssdk.services.s3.S3AsyncClient;

@SuperBuilder
@Setter
@Getter
public class AwsNamedAccountCredentials extends AccountCredentials<AwsCredentials> {

  @NotNull private AwsCredentials credentials;

  private String bucket;
  private String region;
  private String rootFolder;

  @Override
  public String getType() {
    return "aws";
  }

  @JsonIgnore private S3AsyncClient amazonS3;
}
