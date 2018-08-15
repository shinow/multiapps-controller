package com.sap.cloud.lm.sl.cf.process.jobs;

import static java.text.MessageFormat.format;

import java.util.Date;
import java.util.List;

import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.sap.cloud.lm.sl.cf.core.activiti.ActivitiAction;
import com.sap.cloud.lm.sl.cf.core.activiti.ActivitiActionFactory;
import com.sap.cloud.lm.sl.cf.core.activiti.ActivitiFacade;
import com.sap.cloud.lm.sl.cf.core.dao.OperationDao;
import com.sap.cloud.lm.sl.cf.core.dao.filters.OperationFilter;
import com.sap.cloud.lm.sl.cf.web.api.model.Operation;

@Component
@Order(20)
public class OperationsCleaner implements Cleaner {

    private static final Logger LOGGER = LoggerFactory.getLogger(OperationsCleaner.class);
    private static final int DEFAULT_PAGE_SIZE = 100;

    private final OperationDao dao;
    private final ActivitiFacade activitiFacade;
    private int pageSize = DEFAULT_PAGE_SIZE;

    @Inject
    public OperationsCleaner(OperationDao dao, ActivitiFacade activitiFacade) {
        this.dao = dao;
        this.activitiFacade = activitiFacade;
    }

    public OperationsCleaner withPageSize(int pageSize) {
        this.pageSize = pageSize;
        return this;
    }

    @Override
    public void execute(Date expirationTime) {
        LOGGER.info(format("Cleaning up data for operations started before: {0}", expirationTime));
        int abortedOperations = abortOperationsInNonFinalState(expirationTime);
        LOGGER.info(format("Aborted operations: {0}", abortedOperations));
        int deletedOperations = dao.removeExpired(expirationTime);
        LOGGER.info(format("Deleted operations: {0}", deletedOperations));
    }

    private int abortOperationsInNonFinalState(Date expirationTime) {
        int offset = 0;
        while (true) {
            List<Operation> operationsToAbort = getOperationsToAbortPage(expirationTime, offset);
            offset += operationsToAbort.size();
            for (Operation operation : operationsToAbort) {
                abortSafely(operation);
            }
            if (operationsToAbort.size() < pageSize) {
                return offset;
            }
        }
    }

    private List<Operation> getOperationsToAbortPage(Date expirationTime, int firstElement) {
        OperationFilter filter = new OperationFilter.Builder().inNonFinalState()
            .startedBefore(expirationTime)
            .firstElement(firstElement)
            .maxResults(pageSize)
            .build();
        return dao.find(filter);
    }

    private void abortSafely(Operation operation) {
        try {
            abort(operation);
        } catch (Exception e) {
            LOGGER.warn(format("Could not abort operation \"{0}\"", operation.getProcessId()), e);
        }
    }

    private void abort(Operation operation) {
        ActivitiAction abortAction = ActivitiActionFactory.getAction(ActivitiActionFactory.ACTION_ID_ABORT, activitiFacade, null);
        String processId = operation.getProcessId();
        LOGGER.debug(format("Aborting operation \"{0}\"...", processId));
        abortAction.executeAction(processId);
    }

}
