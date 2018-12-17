/*
 * Copyright 2013-2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.cloudfoundry.client.v3;

import org.cloudfoundry.AbstractIntegrationTest;
import org.cloudfoundry.CloudFoundryVersion;
import org.cloudfoundry.IfCloudFoundryVersion;
import org.cloudfoundry.client.CloudFoundryClient;
import org.cloudfoundry.client.v3.applications.ApplicationResource;
import org.cloudfoundry.client.v3.applications.ListApplicationsRequest;
import org.cloudfoundry.client.v3.builds.BuildState;
import org.cloudfoundry.client.v3.builds.CreateBuildRequest;
import org.cloudfoundry.client.v3.builds.CreateBuildResponse;
import org.cloudfoundry.client.v3.builds.GetBuildRequest;
import org.cloudfoundry.client.v3.deployments.*;
import org.cloudfoundry.client.v3.packages.*;
import org.cloudfoundry.client.v3.packages.Package;
import org.cloudfoundry.operations.CloudFoundryOperations;
import org.cloudfoundry.operations.applications.ApplicationManifest;
import org.cloudfoundry.operations.applications.PushApplicationManifestRequest;
import org.cloudfoundry.util.DelayUtils;
import org.cloudfoundry.util.PaginationUtils;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import reactor.core.Exceptions;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.time.Duration;

@IfCloudFoundryVersion(greaterThanOrEqualTo = CloudFoundryVersion.PCF_2_4)
public final class ZDTTest extends AbstractIntegrationTest {

    @Autowired
    private CloudFoundryClient cloudFoundryClient;

    @Autowired
    private CloudFoundryOperations cloudFoundryOperations;

    @Test
    public void zdtPush() throws Exception {
        String applicationName = this.nameFactory.getApplicationName();

        this.cloudFoundryOperations.applications()
                .pushManifest(PushApplicationManifestRequest.builder()
                        .manifest(ApplicationManifest.builder()
                                .path(new ClassPathResource("ar-test/demo-v1.jar").getFile().toPath())
                                .name(applicationName)
                                .build())
                        .build())
                .then(getApplicationId(this.cloudFoundryClient, applicationName))
                .flatMap(applicationId -> this.cloudFoundryClient.packages()
                        .create(CreatePackageRequest.builder()
                                .relationships(PackageRelationships.builder().application(ToOneRelationship.builder().data(Relationship.builder().id(applicationId).build()).build()).build())
                                .type(PackageType.BITS)
                                .build()))
                .map(Package::getId)
                .flatMap(packageId -> {
                    try {
                        return this.cloudFoundryClient.packages()
                                .upload(UploadPackageRequest.builder()
                                        .packageId(packageId)
                                        .bits(new ClassPathResource("ar-test/demo-v2.jar").getFile().toPath())
                                        .build());
                    } catch (IOException e) {
                        throw Exceptions.propagate(e);
                    }
                })

                .map(Package::getId)
                .flatMap(packageId -> this.cloudFoundryClient.packages().get(GetPackageRequest.builder().packageId(packageId).build())
                        .filter(p -> p.getState().equals(PackageState.READY))
                        .repeatWhenEmpty(DelayUtils.exponentialBackOff(Duration.ofSeconds(1), Duration.ofSeconds(15), Duration.ofSeconds(600)))
                )

                .map(Package::getId)
                .flatMap(packageId -> this.cloudFoundryClient.builds().create(CreateBuildRequest.builder().getPackage(Relationship.builder().id(packageId).build()).build())
                        .map(CreateBuildResponse::getId))

                .flatMap(buildId -> this.cloudFoundryClient.builds().get(GetBuildRequest.builder().buildId(buildId).build())
                        .filter(p -> p.getState().equals(BuildState.STAGED))
                        .repeatWhenEmpty(DelayUtils.exponentialBackOff(Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofSeconds(600)))
                )
                .map(response -> response.getDroplet().getId())
                .flatMap(dropletId ->
                        getApplicationId(this.cloudFoundryClient, applicationName)
                                .flatMap(applicationId ->
                                        this.cloudFoundryClient.deploymentsV3()
                                                .create(CreateDeploymentRequest
                                                        .builder()
                                                        .droplet(Relationship
                                                                .builder()
                                                                .id(dropletId).build()).relationships(DeploymentRelationships
                                                                .builder()
                                                                .app(ToOneRelationship
                                                                        .builder()
                                                                        .data(Relationship.builder().id(applicationId).build())
                                                                        .build()
                                                                ).build())
                                                        .build())))
                .map(CreateDeploymentResponse::getId)
                .flatMap(deploymentId -> this.cloudFoundryClient.deploymentsV3().get(GetDeploymentRequest.builder().deploymentId(deploymentId).build())
                        .filter(p -> p.getState().equals("DEPLOYED"))
                        .repeatWhenEmpty(DelayUtils.exponentialBackOff(Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofSeconds(600)))
                )

                .map(GetDeploymentResponse::getNewProcesses)
                .as(StepVerifier::create)
                .expectNextCount(1)
                .expectComplete()
                .verify(Duration.ofMinutes(5));
    }

    private static Mono<String> getApplicationId(CloudFoundryClient cloudFoundryClient, String applicationName) {
        return PaginationUtils.requestClientV3Resources(page -> cloudFoundryClient.applicationsV3()
                .list(ListApplicationsRequest.builder()
                        .name(applicationName)
                        .build()))
                .single()
                .map(ApplicationResource::getId);
    }

}
