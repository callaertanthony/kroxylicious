/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.kubernetes.operator;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.Secret;
import io.javaoperatorsdk.operator.api.config.informer.InformerEventSourceConfiguration;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.ErrorStatusUpdateControl;
import io.javaoperatorsdk.operator.api.reconciler.EventSourceContext;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import io.javaoperatorsdk.operator.processing.event.source.EventSource;
import io.javaoperatorsdk.operator.processing.event.source.informer.InformerEventSource;

import io.kroxylicious.kubernetes.api.common.AnyLocalRef;
import io.kroxylicious.kubernetes.api.common.Condition;
import io.kroxylicious.kubernetes.api.v1alpha1.KafkaService;
import io.kroxylicious.kubernetes.api.v1alpha1.KafkaServiceSpec;
import io.kroxylicious.kubernetes.api.v1alpha1.kafkaservicespec.Tls;
import io.kroxylicious.kubernetes.api.v1alpha1.kafkaservicespec.tls.TrustAnchorRef;

import edu.umd.cs.findbugs.annotations.Nullable;

import static io.kroxylicious.kubernetes.api.common.Condition.Type.ResolvedRefs;
import static io.kroxylicious.kubernetes.operator.ResourcesUtil.name;
import static io.kroxylicious.kubernetes.operator.ResourcesUtil.namespace;

/**
 * <p>Reconciles a {@link KafkaService} by checking whether resources referred to in {@code spec.tls.certificateRefs}
 * actually exist, setting a {@link Condition.Type#ResolvedRefs} {@link Condition} accordingly.</p>
 *
 * <p>Because a service CR is not uniquely associated with a {@code KafkaProxy} it's not possible
 * to set an {@code Accepted} condition on CR instances.</p>
 */
public final class KafkaServiceReconciler implements
        io.javaoperatorsdk.operator.api.reconciler.Reconciler<KafkaService> {

    private static final Logger LOGGER = LoggerFactory.getLogger(KafkaServiceReconciler.class);

    public static final String SECRETS_EVENT_SOURCE_NAME = "secrets";
    public static final String CONFIG_MAPS_EVENT_SOURCE_NAME = "configmaps";

    public static AnyLocalRef asRef(TrustAnchorRef reference) {
        AnyLocalRef result = new AnyLocalRef();
        result.setGroup(reference.getGroup());
        result.setKind(reference.getKind());
        result.setName(reference.getName());
        return result;
    }

    private final KafkaServiceStatusFactory statusFactory;

    public KafkaServiceReconciler(Clock clock) {
        this.statusFactory = new KafkaServiceStatusFactory(clock);
    }

    @Override
    public List<EventSource<?, KafkaService>> prepareEventSources(EventSourceContext<KafkaService> context) {
        InformerEventSourceConfiguration<Secret> serviceToSecret = InformerEventSourceConfiguration.from(
                Secret.class,
                KafkaService.class)
                .withName(SECRETS_EVENT_SOURCE_NAME)
                .withPrimaryToSecondaryMapper((KafkaService cluster) -> ResourcesUtil.localRefAsResourceId(cluster, cluster.getSpec().getTls().getCertificateRef()))
                .withSecondaryToPrimaryMapper(secret -> ResourcesUtil.findReferrers(context,
                        secret,
                        KafkaService.class,
                        service -> service.getSpec().getTls().getCertificateRef()))
                .build();
        InformerEventSourceConfiguration<ConfigMap> serviceToConfigMap = InformerEventSourceConfiguration.from(
                ConfigMap.class,
                KafkaService.class)
                .withName(CONFIG_MAPS_EVENT_SOURCE_NAME)
                .withPrimaryToSecondaryMapper(
                        (KafkaService cluster) -> ResourcesUtil.localRefAsResourceId(cluster, asRef(cluster.getSpec().getTls().getTrustAnchorRef())))
                .withSecondaryToPrimaryMapper(configMap -> ResourcesUtil.findReferrers(context,
                        configMap,
                        KafkaService.class,
                        service -> asRef(service.getSpec().getTls().getTrustAnchorRef())))
                .build();
        return List.of(
                new InformerEventSource<>(serviceToSecret, context),
                new InformerEventSource<>(serviceToConfigMap, context));
    }

    @Override
    public UpdateControl<KafkaService> reconcile(KafkaService service, Context<KafkaService> context) {

        KafkaService updatedService = null;

        var trustAnchorRefOpt = Optional.ofNullable(service.getSpec())
                .map(KafkaServiceSpec::getTls)
                .map(Tls::getTrustAnchorRef);
        if (trustAnchorRefOpt.isPresent()) {
            updatedService = checkTrustAnchorRef(service, context, trustAnchorRefOpt.get());
        }

        if (updatedService == null) {
            var certRefOpt = Optional.ofNullable(service.getSpec())
                    .map(KafkaServiceSpec::getTls)
                    .map(Tls::getCertificateRef);
            if (certRefOpt.isPresent()) {
                updatedService = checkCertRef(service, context, certRefOpt.get());
            }
        }

        if (updatedService == null) {
            updatedService = statusFactory.newTrueConditionStatusPatch(service, ResolvedRefs);
        }

        UpdateControl<KafkaService> uc = UpdateControl.patchStatus(updatedService);

        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("Completed reconciliation of {}/{}", namespace(service), name(service));
        }
        return uc;
    }

    @Nullable
    private KafkaService checkTrustAnchorRef(KafkaService service,
                                             Context<KafkaService> context,
                                             TrustAnchorRef trustAnchorRef) {
        String path = "spec.tls.trustAnchorRef";
        if (ResourcesUtil.isConfigMap(trustAnchorRef)) {
            Optional<ConfigMap> configMapOpt = context.getSecondaryResource(ConfigMap.class, CONFIG_MAPS_EVENT_SOURCE_NAME);
            if (configMapOpt.isEmpty()) {
                return statusFactory.newFalseConditionStatusPatch(service, ResolvedRefs,
                        Condition.REASON_REFS_NOT_FOUND,
                        path + ": referenced resource not found");
            }
            else {
                String key = trustAnchorRef.getKey();
                if (key == null) {
                    return statusFactory.newFalseConditionStatusPatch(service, ResolvedRefs,
                            Condition.REASON_INVALID,
                            path + " must specify 'key'");
                }
                if (!key.endsWith(".pem")
                        && !key.endsWith(".p12")
                        && !key.endsWith(".jks")) {
                    return statusFactory.newFalseConditionStatusPatch(service, ResolvedRefs,
                            Condition.REASON_INVALID,
                            path + ".key should end with .pem, .p12 or .jks");
                }
                else if (!configMapOpt.get().getData().containsKey(trustAnchorRef.getKey())) {
                    return statusFactory.newFalseConditionStatusPatch(service, ResolvedRefs,
                            Condition.REASON_INVALID_REFERENCED_RESOURCE,
                            path + ": referenced resource does not contain key " + trustAnchorRef.getKey());
                }
            }
        }
        else {
            return statusFactory.newFalseConditionStatusPatch(service, ResolvedRefs,
                    Condition.REASON_REF_GROUP_KIND_NOT_SUPPORTED,
                    "spec.tls.trustAnchorRef supports referents: configmaps");
        }
        return null;
    }

    @Nullable
    private KafkaService checkCertRef(KafkaService service,
                                      Context<KafkaService> context,
                                      AnyLocalRef certRef) {
        String path = "spec.tls.certificateRef";
        if (ResourcesUtil.isSecret(certRef)) {
            Optional<Secret> secretOpt = context.getSecondaryResource(Secret.class, SECRETS_EVENT_SOURCE_NAME);
            if (secretOpt.isEmpty()) {
                return statusFactory.newFalseConditionStatusPatch(service, ResolvedRefs,
                        Condition.REASON_REFS_NOT_FOUND,
                        path + ": referenced resource not found");
            }
            else {
                if (!"kubernetes.io/tls".equals(secretOpt.get().getType())) {
                    return statusFactory.newFalseConditionStatusPatch(service, ResolvedRefs,
                            Condition.REASON_INVALID_REFERENCED_RESOURCE,
                            path + ": referenced secret should have 'type: kubernetes.io/tls'");
                }
            }
        }
        else {
            return statusFactory.newFalseConditionStatusPatch(service, ResolvedRefs,
                    Condition.REASON_REF_GROUP_KIND_NOT_SUPPORTED,
                    path + " supports referents: secrets");
        }
        return null;
    }

    @Override
    public ErrorStatusUpdateControl<KafkaService> updateErrorStatus(KafkaService service, Context<KafkaService> context, Exception e) {
        // ResolvedRefs to UNKNOWN
        ErrorStatusUpdateControl<KafkaService> uc = ErrorStatusUpdateControl
                .patchStatus(statusFactory.newUnknownConditionStatusPatch(service, ResolvedRefs, e));
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("Completed reconciliation of {}/{} with error {}", namespace(service), name(service), e.toString());
        }
        return uc;
    }
}
