/*
 * Copyright (c) 2012-2018 Red Hat, Inc.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *   Red Hat, Inc. - initial API and implementation
 */
package org.eclipse.che.workspace.infrastructure.kubernetes.provision;

import static com.google.common.base.Strings.isNullOrEmpty;

import com.google.common.base.Splitter;
import com.google.common.collect.Lists;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.ConfigMapVolumeSourceBuilder;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.VolumeBuilder;
import io.fabric8.kubernetes.api.model.VolumeMountBuilder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import org.eclipse.che.api.workspace.server.spi.InfrastructureException;
import org.eclipse.che.commons.annotation.Nullable;
import org.eclipse.che.workspace.infrastructure.kubernetes.CheServerKubernetesClientFactory;
import org.eclipse.che.workspace.infrastructure.kubernetes.environment.CheInstallationLocation;
import org.eclipse.che.workspace.infrastructure.kubernetes.environment.KubernetesEnvironment;
import org.eclipse.che.workspace.infrastructure.kubernetes.environment.KubernetesEnvironment.PodData;
import org.eclipse.che.workspace.infrastructure.kubernetes.environment.KubernetesEnvironment.PodRole;
import org.eclipse.che.workspace.infrastructure.kubernetes.namespace.KubernetesNamespace;

/**
 * Checks if config maps with CA bundles is configured by specific property. If they are, then
 * creates single config map for all ca bundles in workspace namespace and mounts it into pods.
 */
@Singleton
public class KubernetesTrustedCAProvisioner implements TrustedCAProvisioner {

  public static final String CHE_TRUST_STORE_VOLUME = "che-ca-certs";

  private final String certificateMountPath;
  private final boolean trustedStoreInitialized;
  private final List<String> caBundleConfigMaps;
  private final String configMapName;
  private final CheServerKubernetesClientFactory cheServerClientFactory;
  private final String installationLocationNamespace;

  protected Map<String, String> configMapLabelKeyValue;

  @Inject
  public KubernetesTrustedCAProvisioner(
      @Nullable @Named("che.trusted_ca_bundles_configmap") String caBundleConfigMaps,
      @Named("che.infra.kubernetes.trusted_ca_bundles_config_map") String configMapName,
      @Named("che.infra.kubernetes.trusted_ca_bundles_mount_path") String certificateMountPath,
      CheInstallationLocation cheInstallationLocation,
      CheServerKubernetesClientFactory cheServerClientFactory)
      throws InfrastructureException {
    this.cheServerClientFactory = cheServerClientFactory;
    this.trustedStoreInitialized = !isNullOrEmpty(caBundleConfigMaps);
    this.caBundleConfigMaps =
        this.trustedStoreInitialized
            ? Lists.newArrayList(Splitter.on(",").split(caBundleConfigMaps))
            : new ArrayList<>();
    this.configMapName = configMapName;
    this.certificateMountPath = certificateMountPath;
    this.installationLocationNamespace = cheInstallationLocation.getInstallationLocationNamespace();

    this.configMapLabelKeyValue = new HashMap<>();
  }

  public boolean isTrustedStoreInitialized() {
    return trustedStoreInitialized;
  }

  public void provision(KubernetesEnvironment k8sEnv, KubernetesNamespace namespace)
      throws InfrastructureException {
    if (!trustedStoreInitialized) {
      return;
    }

    ConfigMap caConfigMap =
        cheServerClientFactory
            .create()
            .configMaps()
            .inNamespace(installationLocationNamespace)
            .withName(configMapName)
            .get();
    if (caConfigMap == null) {
      return;
    }

    for (String configMapName : this.caBundleConfigMaps) {
      ConfigMap configMap =
          cheServerClientFactory
              .create()
              .configMaps()
              .inNamespace(installationLocationNamespace)
              .withName(configMapName)
              .get();
      // Add data from config map into resulting CA-bundle config map
      // Add original config map name into key to avoid conflicts
      configMap
          .getData()
          .forEach((key, value) -> caConfigMap.getData().put(configMapName + "." + key, value));
    }

    Optional<ConfigMap> existing = namespace.configMaps().get(configMapName);

    if (existing.isEmpty() || !existing.get().getData().equals(caConfigMap.getData())) {
      // create or renew map
      k8sEnv
          .getConfigMaps()
          .put(
              configMapName,
              new ConfigMapBuilder()
                  .withMetadata(
                      new ObjectMetaBuilder()
                          .withName(configMapName)
                          .withAnnotations(caConfigMap.getMetadata().getAnnotations())
                          .withLabels(configMapLabelKeyValue)
                          .build())
                  .withApiVersion(caConfigMap.getApiVersion())
                  .withData(caConfigMap.getData())
                  .build());
    }

    for (PodData pod : k8sEnv.getPodsData().values()) {
      if (pod.getRole() == PodRole.DEPLOYMENT) {
        if (pod.getSpec()
            .getVolumes()
            .stream()
            .noneMatch(v -> v.getName().equals(CHE_TRUST_STORE_VOLUME))) {
          pod.getSpec()
              .getVolumes()
              .add(
                  new VolumeBuilder()
                      .withName(CHE_TRUST_STORE_VOLUME)
                      .withConfigMap(
                          new ConfigMapVolumeSourceBuilder().withName(configMapName).build())
                      .build());
        }
      }
      for (Container container : pod.getSpec().getInitContainers()) {
        provisionTrustStoreVolumeMountIfNeeded(container);
      }
      for (Container container : pod.getSpec().getContainers()) {
        provisionTrustStoreVolumeMountIfNeeded(container);
      }
    }
  }

  private void provisionTrustStoreVolumeMountIfNeeded(Container container) {
    if (container
        .getVolumeMounts()
        .stream()
        .noneMatch(vm -> vm.getName().equals(CHE_TRUST_STORE_VOLUME))) {
      container
          .getVolumeMounts()
          .add(
              new VolumeMountBuilder()
                  .withName(CHE_TRUST_STORE_VOLUME)
                  .withNewReadOnly(true)
                  .withMountPath(certificateMountPath)
                  .build());
    }
  }
}
