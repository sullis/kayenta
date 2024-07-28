/*
 * Copyright 2024 Netflix, Inc.
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

package com.netflix.kayenta.aws2.config;

import static java.util.Optional.ofNullable;

import com.netflix.kayenta.aws2.security.AwsCredentials;
import com.netflix.kayenta.aws2.security.AwsNamedAccountCredentials;
import com.netflix.kayenta.security.AccountCredentials;
import com.netflix.kayenta.security.AccountCredentialsRepository;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.Protocol;
import software.amazon.awssdk.core.client.config.SdkClientConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3AsyncClientBuilder;

@Configuration
@ConditionalOnProperty("kayenta.aws2.enabled")
@ComponentScan({"com.netflix.kayenta.aws2"})
@Slf4j
public class AwsConfiguration {

  @Bean
  @ConfigurationProperties("kayenta.aws2")
  AwsConfigurationProperties awsConfigurationProperties() {
    return new AwsConfigurationProperties();
  }

  @Bean
  boolean registerAwsCredentials(
      AwsConfigurationProperties awsConfigurationProperties,
      AccountCredentialsRepository accountCredentialsRepository)
      throws IOException {
    for (AwsManagedAccount awsManagedAccount : awsConfigurationProperties.getAccounts()) {
      String name = awsManagedAccount.getName();
      List<AccountCredentials.Type> supportedTypes = awsManagedAccount.getSupportedTypes();

      log.info("Registering AWS account {} with supported types {}.", name, supportedTypes);

      SdkClientConfiguration.Builder clientConfigurationBuilder = SdkClientConfiguration.builder();

      if (awsManagedAccount.getProxyProtocol() != null) {
        if (awsManagedAccount.getProxyProtocol().equalsIgnoreCase("HTTPS")) {
          clientConfigurationBuilder.setProtocol(Protocol.HTTPS);
        } else {
          clientConfigurationBuilder.setProtocol(Protocol.HTTP);
        }
        ofNullable(awsManagedAccount.getProxyHost()).ifPresent(clientConfiguration::setProxyHost);
        ofNullable(awsManagedAccount.getProxyPort())
            .map(Integer::parseInt)
            .ifPresent(clientConfiguration::setProxyPort);
      }

      S3AsyncClientBuilder amazonS3ClientBuilder = S3AsyncClient.builder();
      String profileName = awsManagedAccount.getProfileName();

      if (!StringUtils.isEmpty(profileName)) {
        amazonS3ClientBuilder.credentialsProvider(ProfileCredentialsProvider.create(profileName));
      }

      AwsManagedAccount.ExplicitAwsCredentials explicitCredentials =
          awsManagedAccount.getExplicitCredentials();
      if (explicitCredentials != null) {
        String sessionToken = explicitCredentials.getSessionToken();
        software.amazon.awssdk.auth.credentials.AwsCredentials awsCreds =
            (sessionToken == null)
                ? AwsBasicCredentials.create(
                    explicitCredentials.getAccessKey(), explicitCredentials.getSecretKey())
                : AwsSessionCredentials.builder()
                    .accessKeyId(explicitCredentials.getAccessKey())
                    .secretAccessKey(explicitCredentials.getSecretKey())
                    .sessionToken(sessionToken)
                    .build();
        amazonS3ClientBuilder.credentialsProvider(StaticCredentialsProvider.create(awsCreds));
      }

      String endpoint = awsManagedAccount.getEndpoint();

      if (!StringUtils.isEmpty(endpoint)) {
        amazonS3ClientBuilder.endpointOverride(URI.create(endpoint));
        amazonS3ClientBuilder.forcePathStyle(true);
      } else {
        Optional.ofNullable(awsManagedAccount.getRegion())
            .ifPresent(r -> amazonS3ClientBuilder.region(Region.of(r)));
      }

      S3AsyncClient amazonS3 = amazonS3ClientBuilder.build();

      try {
        AwsCredentials awsCredentials = new AwsCredentials();
        AwsNamedAccountCredentials.AwsNamedAccountCredentialsBuilder
            awsNamedAccountCredentialsBuilder =
                AwsNamedAccountCredentials.builder().name(name).credentials(awsCredentials);

        if (!CollectionUtils.isEmpty(supportedTypes)) {
          if (supportedTypes.contains(AccountCredentials.Type.OBJECT_STORE)) {
            String bucket = awsManagedAccount.getBucket();
            String rootFolder = awsManagedAccount.getRootFolder();

            if (StringUtils.isEmpty(bucket)) {
              throw new IllegalArgumentException(
                  "AWS/S3 account " + name + " is required to specify a bucket.");
            }

            if (StringUtils.isEmpty(rootFolder)) {
              throw new IllegalArgumentException(
                  "AWS/S3 account " + name + " is required to specify a rootFolder.");
            }

            awsNamedAccountCredentialsBuilder.bucket(bucket);
            awsNamedAccountCredentialsBuilder.region(awsManagedAccount.getRegion());
            awsNamedAccountCredentialsBuilder.rootFolder(rootFolder);
            awsNamedAccountCredentialsBuilder.amazonS3(amazonS3);
          }

          awsNamedAccountCredentialsBuilder.supportedTypes(supportedTypes);
        }

        AwsNamedAccountCredentials awsNamedAccountCredentials =
            awsNamedAccountCredentialsBuilder.build();
        accountCredentialsRepository.save(name, awsNamedAccountCredentials);
      } catch (Throwable t) {
        log.error("Could not load AWS account " + name + ".", t);
      }
    }

    return true;
  }
}
