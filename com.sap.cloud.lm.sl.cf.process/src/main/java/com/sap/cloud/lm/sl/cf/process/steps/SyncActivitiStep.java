package com.sap.cloud.lm.sl.cf.process.steps;

import javax.inject.Inject;
import javax.inject.Named;

import org.activiti.engine.delegate.DelegateExecution;
import org.activiti.engine.delegate.JavaDelegate;
import org.slf4j.MDC;

import com.sap.activiti.common.ExecutionStatus;
import com.sap.activiti.common.Logger;
import com.sap.activiti.common.api.IStatusSignaller;
import com.sap.cloud.lm.sl.cf.core.Constants;
import com.sap.cloud.lm.sl.cf.core.cf.CloudFoundryClientProvider;
import com.sap.cloud.lm.sl.cf.core.dao.ContextExtensionDao;
import com.sap.cloud.lm.sl.cf.process.exception.MonitoringException;
import com.sap.cloud.lm.sl.cf.process.message.Messages;
import com.sap.cloud.lm.sl.cf.process.util.StepLogger;
import com.sap.cloud.lm.sl.common.SLException;
import com.sap.cloud.lm.sl.persistence.services.AbstractFileService;
import com.sap.cloud.lm.sl.persistence.services.ProcessLoggerProviderFactory;
import com.sap.cloud.lm.sl.persistence.services.ProgressMessageService;

public abstract class SyncActivitiStep implements StepIndexProvider, JavaDelegate {

    protected final Logger LOGGER = Logger.getInstance(getClass());

    @Inject
    protected CloudFoundryClientProvider clientProvider;
    @Inject
    protected ContextExtensionDao contextExtensionDao;
    @Inject
    private StepLogger.Factory stepLoggerFactory;
    @Inject
    protected ProcessLoggerProviderFactory processLoggerProviderFactory;
    @Inject
    protected ProgressMessageService progressMessageService;
    @Inject
    @Named("fileService")
    protected AbstractFileService fileService;
    protected ProcessStepHelper stepHelper;
    private StepLogger stepLogger;

    private IStatusSignaller signaller;

    @Override
    public void execute(DelegateExecution context) throws Exception {
        ExecutionStatus status = null;
        createStepLogger(context);
        try {
            MDC.put(Constants.ATTR_CORRELATION_ID, StepsUtil.getCorrelationId(context));
            getStepHelper().preExecuteStep(context, ExecutionStatus.NEW);
            status = executeStep(createExecutionWrapper(context));
            getStepHelper().failStepIfProcessIsAborted(context);
            LOGGER.debug(context, "Execution finished");
        } catch (MonitoringException e) {
            getStepLogger().errorWithoutProgressMessage(e.getMessage());
            status = ExecutionStatus.FAILED;
            handleException(context, e);
        } catch (Throwable t) {
            status = ExecutionStatus.FAILED;
            handleException(context, t);
        } finally {
            postExecuteStep(context, status);
        }
    }

    protected ExecutionWrapper createExecutionWrapper(DelegateExecution context) {
        return new ExecutionWrapper(context, contextExtensionDao, stepLogger, clientProvider, processLoggerProviderFactory);
    }

    private void handleException(DelegateExecution context, Throwable t) throws Exception {
        t = getWithProperMessage(t);
        getStepHelper().logException(context, t);
        throw t instanceof Exception ? (Exception) t : new Exception(t);
    }

    protected void postExecuteStep(DelegateExecution context, ExecutionStatus status) {
        try {
            getStepHelper().postExecuteStep(context, status);
        } catch (SLException e) {
            getStepHelper().storeExceptionInProgressMessageService(context, e);
            logException(context, e);
            e.printStackTrace();
            throw e;
        }
    }

    protected abstract ExecutionStatus executeStep(ExecutionWrapper execution) throws Exception;

    protected StepLogger getStepLogger() {
        if (stepLogger == null) {
            throw new IllegalStateException(Messages.STEP_LOGGER_NOT_INITIALIZED);
        }
        return stepLogger;
    }

    protected void createStepLogger(DelegateExecution context) {
        stepLogger = stepLoggerFactory.create(context, progressMessageService, processLoggerProviderFactory, LOGGER.getLoggerImpl());
    }

    protected Throwable getWithProperMessage(Throwable t) {
        if (t.getMessage() == null || t.getMessage().isEmpty()) {
            return new Exception("An unknown error occurred", t);
        }
        return t;
    }

    public void logException(DelegateExecution context, Throwable t) {
        getStepHelper().logException(context, t);
    }

    protected ProcessStepHelper getStepHelper() {
        if (stepHelper == null) {
            stepHelper = new ProcessStepHelper(getProgressMessageService(), getProcessLoggerProvider(), this, contextExtensionDao);
        }
        return stepHelper;
    }

    protected ProcessLoggerProviderFactory getProcessLoggerProvider() {
        return processLoggerProviderFactory;
    }

    protected ProgressMessageService getProgressMessageService() {
        return progressMessageService;
    }

    @Override
    public Integer getStepIndex(DelegateExecution context) {
        return getIndexVariable() != null ? (int) context.getVariable(getIndexVariable()) : -1;
    }

    protected String getIndexVariable() {
        return null;
    }

    public IStatusSignaller getSignaller() {
        return this.signaller;
    }

}