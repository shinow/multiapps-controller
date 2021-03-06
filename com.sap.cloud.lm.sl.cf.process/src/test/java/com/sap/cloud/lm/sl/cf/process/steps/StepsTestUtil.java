package com.sap.cloud.lm.sl.cf.process.steps;

import java.util.ArrayList;
import java.util.List;

import org.flowable.engine.delegate.DelegateExecution;
import org.mockito.Mockito;

import com.sap.cloud.lm.sl.cf.client.lib.domain.CloudApplicationExtended;
import com.sap.cloud.lm.sl.cf.process.variables.Variables;
import com.sap.cloud.lm.sl.common.util.JsonUtil;

public class StepsTestUtil {

    public static void mockApplicationsToDeploy(List<CloudApplicationExtended> applications, DelegateExecution execution) {
        String[] appsInArray = getAppsInArray(applications);
        for (String appInArray : appsInArray) {
            // FIXME: This does not work! It will always return the last app in the array.
            Mockito.when(execution.getVariable(Variables.APP_TO_PROCESS.getName()))
                   .thenReturn(appInArray);
        }
    }

    private static String[] getAppsInArray(List<CloudApplicationExtended> applications) {
        List<String> applicationsString = new ArrayList<>();
        for (CloudApplicationExtended app : applications) {
            applicationsString.add(JsonUtil.toJson(app));
        }
        return applicationsString.toArray(new String[0]);
    }

}
