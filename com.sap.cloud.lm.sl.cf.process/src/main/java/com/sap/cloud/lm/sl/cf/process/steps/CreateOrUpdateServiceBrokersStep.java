package com.sap.cloud.lm.sl.cf.process.steps;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import javax.inject.Inject;

import org.activiti.engine.delegate.DelegateExecution;
import org.cloudfoundry.client.lib.CloudFoundryException;
import org.cloudfoundry.client.lib.CloudFoundryOperations;
import org.cloudfoundry.client.lib.domain.CloudApplication;
import org.cloudfoundry.client.lib.domain.CloudServiceBroker;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import com.sap.activiti.common.ExecutionStatus;
import com.sap.cloud.lm.sl.cf.client.lib.domain.CloudApplicationExtended;
import com.sap.cloud.lm.sl.cf.client.lib.domain.CloudServiceBrokerExtended;
import com.sap.cloud.lm.sl.cf.core.cf.PlatformType;
import com.sap.cloud.lm.sl.cf.core.cf.clients.ServiceBrokerCreator;
import com.sap.cloud.lm.sl.cf.core.cf.clients.ServiceBrokersGetter;
import com.sap.cloud.lm.sl.cf.core.helpers.ApplicationAttributesGetter;
import com.sap.cloud.lm.sl.cf.core.model.SupportedParameters;
import com.sap.cloud.lm.sl.cf.core.security.serialization.SecureSerializationFacade;
import com.sap.cloud.lm.sl.cf.core.util.ConfigurationUtil;
import com.sap.cloud.lm.sl.cf.process.Constants;
import com.sap.cloud.lm.sl.cf.process.message.Messages;
import com.sap.cloud.lm.sl.common.SLException;
import com.sap.cloud.lm.sl.slp.model.StepMetadata;

@Component("createOrUpdateServiceBrokersStep")
@Scope(BeanDefinition.SCOPE_PROTOTYPE)
public class CreateOrUpdateServiceBrokersStep extends AbstractXS2ProcessStep {

    @Inject
    private ServiceBrokerCreator serviceBrokerCreator;
    @Inject
    private ServiceBrokersGetter serviceBrokersGetter;
    private SecureSerializationFacade secureSerializer = new SecureSerializationFacade();
    protected Supplier<PlatformType> platformTypeSupplier = () -> ConfigurationUtil.getPlatformType();

    public static StepMetadata getMetadata() {
        return StepMetadata.builder().id("createOrUpdateServiceBrokersTask").displayName("Create Or Update Service Brokers").description(
            "Create Or Update Service Brokers").build();
    }

    @Override
    protected ExecutionStatus executeStepInternal(DelegateExecution context) throws SLException {
        getStepLogger().logActivitiTask();
        try {
            getStepLogger().info(Messages.CREATING_SERVICE_BROKERS);

            CloudFoundryOperations client = getCloudFoundryClient(context);
            List<CloudServiceBrokerExtended> existingServiceBrokers = serviceBrokersGetter.getServiceBrokers(client);
            List<CloudServiceBrokerExtended> serviceBrokersToCreate = getServiceBrokersToCreate(StepsUtil.getAppsToDeploy(context),
                context);
            getStepLogger().debug(MessageFormat.format(Messages.SERVICE_BROKERS, secureSerializer.toJson(serviceBrokersToCreate)));
            List<String> existingServiceBrokerNames = getServiceBrokerNames(existingServiceBrokers);

            for (CloudServiceBrokerExtended serviceBroker : serviceBrokersToCreate) {
                if (existingServiceBrokerNames.contains(serviceBroker.getName())) {
                    CloudServiceBrokerExtended existingBroker = findServiceBroker(existingServiceBrokers, serviceBroker.getName());
                    updateServiceBroker(context, serviceBroker, existingBroker, client);
                } else {
                    createServiceBroker(context, serviceBroker, client);
                }
            }

            StepsUtil.setServiceBrokersToCreate(context, serviceBrokersToCreate);
            getStepLogger().debug(Messages.SERVICE_BROKERS_CREATED);
            return ExecutionStatus.SUCCESS;
        } catch (SLException e) {
            getStepLogger().error(e, Messages.ERROR_CREATING_SERVICE_BROKERS);
            throw e;
        } catch (CloudFoundryException cfe) {
            SLException e = StepsUtil.createException(cfe);
            getStepLogger().error(e, Messages.ERROR_CREATING_SERVICE_BROKERS);
            throw e;
        }
    }

    private void updateServiceBroker(DelegateExecution context, CloudServiceBrokerExtended serviceBroker,
        CloudServiceBrokerExtended existingBroker, CloudFoundryOperations client) {
        serviceBroker.setMeta(existingBroker.getMeta());
        if (existingBroker.getSpaceGuid() != null && serviceBroker.getSpaceGuid() == null) {
            getStepLogger().warn(MessageFormat.format(Messages.CANNOT_CHANGE_VISIBILITY_OF_SERVICE_BROKER_FROM_SPACE_SCOPED_TO_GLOBAL,
                serviceBroker.getName()));
        } else if (existingBroker.getSpaceGuid() == null && serviceBroker.getSpaceGuid() != null) {
            getStepLogger().warn(MessageFormat.format(Messages.CANNOT_CHANGE_VISIBILITY_OF_SERVICE_BROKER_FROM_GLOBAL_TO_SPACE_SCOPED,
                serviceBroker.getName()));
        }
        updateServiceBroker(context, serviceBroker, client);
    }

    private CloudServiceBrokerExtended findServiceBroker(List<CloudServiceBrokerExtended> serviceBrokers, String name) {
        return serviceBrokers.stream().filter((broker) -> broker.getName().equals(name)).findFirst().get();
    }

    private List<CloudServiceBrokerExtended> getServiceBrokersToCreate(List<CloudApplicationExtended> appsToDeploy,
        DelegateExecution context) throws SLException {
        List<CloudServiceBrokerExtended> serviceBrokersToCreate = new ArrayList<>();
        for (CloudApplicationExtended app : appsToDeploy) {
            CloudServiceBrokerExtended serviceBroker = getServiceBrokerFromApp(app, context);
            if (serviceBroker == null) {
                continue;
            }
            String msg = MessageFormat.format(Messages.CONSTRUCTED_SERVICE_BROKER_FROM_APPLICATION, serviceBroker.getName(), app.getName());
            getStepLogger().debug(msg);
            serviceBrokersToCreate.add(serviceBroker);
        }
        return serviceBrokersToCreate;
    }

    protected CloudServiceBrokerExtended getServiceBrokerFromApp(CloudApplication app, DelegateExecution context) throws SLException {
        ApplicationAttributesGetter attributesGetter = ApplicationAttributesGetter.forApplication(app);
        if (!attributesGetter.getAttribute(SupportedParameters.CREATE_SERVICE_BROKER, Boolean.class, false)) {
            return null;
        }

        String serviceBrokerName = attributesGetter.getAttribute(SupportedParameters.SERVICE_BROKER_NAME, String.class, app.getName());
        String serviceBrokerUrl = attributesGetter.getAttribute(SupportedParameters.SERVICE_BROKER_URL, String.class);
        String serviceBrokerPassword = attributesGetter.getAttribute(SupportedParameters.SERVICE_BROKER_PASSWORD, String.class);
        String serviceBrokerUser = attributesGetter.getAttribute(SupportedParameters.SERVICE_BROKER_USER, String.class);
        String serviceBrokerSpaceGuid = getServiceBrokerSpaceGuid(context, serviceBrokerName, attributesGetter);

        if (serviceBrokerUser == null) {
            throw new SLException(Messages.MISSING_SERVICE_BROKER_USER, app.getName());
        }
        if (serviceBrokerUrl == null) {
            throw new SLException(Messages.MISSING_SERVICE_BROKER_URL, app.getName());
        }
        if (serviceBrokerName == null) {
            throw new SLException(Messages.MISSING_SERVICE_BROKER_NAME, app.getName());
        }
        if (serviceBrokerPassword == null) {
            throw new SLException(Messages.MISSING_SERVICE_BROKER_PASSWORD, app.getName());
        }

        return getCloudServiceBroker(serviceBrokerName, serviceBrokerUser, serviceBrokerPassword, serviceBrokerUrl, serviceBrokerSpaceGuid);
    }

    private String getServiceBrokerSpaceGuid(DelegateExecution context, String serviceBrokerName,
        ApplicationAttributesGetter attributesGetter) {
        PlatformType platformType = platformTypeSupplier.get();
        boolean isSpaceScoped = attributesGetter.getAttribute(SupportedParameters.SERVICE_BROKER_SPACE_SCOPED, Boolean.class, false);
        if (platformType == PlatformType.XS2 && isSpaceScoped) {
            getStepLogger().warn(
                MessageFormat.format(Messages.CANNOT_CREATE_SPACE_SCOPED_SERVICE_BROKER_ON_THIS_PLATFORM, serviceBrokerName));
            return null;
        }
        return isSpaceScoped ? StepsUtil.getSpaceId(context) : null;
    }

    public static List<String> getServiceBrokerNames(List<? extends CloudServiceBroker> serviceBrokers) {
        return serviceBrokers.stream().map((broker) -> broker.getName()).collect(Collectors.toList());
    }

    protected void updateServiceBroker(DelegateExecution context, CloudServiceBroker serviceBroker, CloudFoundryOperations client) {
        try {
            getStepLogger().info(MessageFormat.format(Messages.UPDATING_SERVICE_BROKER, serviceBroker.getName()));
            client.updateServiceBroker(serviceBroker);
            getStepLogger().debug(MessageFormat.format(Messages.UPDATED_SERVICE_BROKER, serviceBroker.getName()));
        } catch (CloudFoundryException e) {
            switch (e.getStatusCode()) {
                case NOT_IMPLEMENTED:
                    getStepLogger().warn(Messages.UPDATE_OF_SERVICE_BROKERS_FAILED_501, serviceBroker.getName());
                    break;
                case FORBIDDEN:
                    if (shouldSucceed(context)) {
                        getStepLogger().warn(Messages.UPDATE_OF_SERVICE_BROKERS_FAILED_403, serviceBroker.getName());
                        return;
                    }
                default:
                    throw e;
            }
        }
    }

    private void createServiceBroker(DelegateExecution context, CloudServiceBrokerExtended serviceBroker, CloudFoundryOperations client) {
        try {
            getStepLogger().info(MessageFormat.format(Messages.CREATING_SERVICE_BROKER, serviceBroker.getName()));
            serviceBrokerCreator.createServiceBroker(client, serviceBroker);
            getStepLogger().debug(MessageFormat.format(Messages.CREATED_SERVICE_BROKER, serviceBroker.getName()));
        } catch (CloudFoundryException e) {
            switch (e.getStatusCode()) {
                case FORBIDDEN:
                    if (shouldSucceed(context)) {
                        getStepLogger().warn(Messages.CREATE_OF_SERVICE_BROKERS_FAILED_403, serviceBroker.getName());
                        return;
                    }
                default:
                    throw e;
            }
        }
    }

    private CloudServiceBrokerExtended getCloudServiceBroker(String name, String user, String password, String url, String spaceGuid) {
        return new CloudServiceBrokerExtended(name, url, user, password, spaceGuid);
    }

    private boolean shouldSucceed(DelegateExecution context) {
        return (Boolean) context.getVariable(Constants.PARAM_NO_FAIL_ON_MISSING_PERMISSIONS);
    }

}