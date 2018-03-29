/*
 * #%L
 * Alfresco Remote API
 * %%
 * Copyright (C) 2005 - 2016 Alfresco Software Limited
 * %%
 * This file is part of the Alfresco software. 
 * If the software was purchased under a paid Alfresco license, the terms of 
 * the paid license agreement will prevail.  Otherwise, the software is 
 * provided under the following open source license terms:
 * 
 * Alfresco is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * Alfresco is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public License
 * along with Alfresco. If not, see <http://www.gnu.org/licenses/>.
 * #L%
 */
package org.alfresco.rest.workflow.api.tests;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.activiti.engine.TaskService;
import org.activiti.engine.history.HistoricTaskInstance;
import org.activiti.engine.runtime.Clock;
import org.activiti.engine.runtime.ProcessInstance;
import org.activiti.engine.task.DelegationState;
import org.activiti.engine.task.IdentityLinkType;
import org.activiti.engine.task.Task;
import org.alfresco.model.ContentModel;
import org.alfresco.repo.security.authentication.AuthenticationUtil;
import org.alfresco.repo.tenant.TenantUtil;
import org.alfresco.repo.tenant.TenantUtil.TenantRunAsWork;
import org.alfresco.repo.transaction.RetryingTransactionHelper;
import org.alfresco.repo.workflow.WorkflowConstants;
import org.alfresco.repo.workflow.activiti.ActivitiConstants;
import org.alfresco.repo.workflow.activiti.ActivitiScriptNode;
import org.alfresco.rest.api.tests.PersonInfo;
import org.alfresco.rest.api.tests.RepoService.TestNetwork;
import org.alfresco.rest.api.tests.RepoService.TestPerson;
import org.alfresco.rest.api.tests.RepoService.TestSite;
import org.alfresco.rest.api.tests.client.HttpResponse;
import org.alfresco.rest.api.tests.client.PublicApiException;
import org.alfresco.rest.api.tests.client.RequestContext;
import org.alfresco.rest.api.tests.client.data.MemberOfSite;
import org.alfresco.rest.api.tests.client.data.SiteRole;
import org.alfresco.rest.workflow.api.model.ProcessInfo;
import org.alfresco.rest.workflow.api.tests.WorkflowApiClient.TasksClient;
import org.alfresco.service.cmr.repository.NodeRef;
import org.alfresco.test_category.OwnJVMTestsCategory;
import org.alfresco.util.ISO8601DateFormat;
import org.alfresco.util.json.jackson.AlfrescoDefaultObjectMapper;
import org.apache.commons.lang.StringUtils;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.springframework.http.HttpStatus;

/**
 * Task related Rest api tests using http client to communicate with the rest apis in the repository.
 * 
 * @author Tijs Rademakers
 * @author Frederik Heremans
 *
 */
@Category(OwnJVMTestsCategory.class)
public class TaskWorkflowApiTest extends EnterpriseWorkflowTestApi
{   
    @Test
    public void testGetTaskById() throws Exception
    {
        RequestContext requestContext = initApiClientWithTestUser();
        
        String otherPerson = getOtherPersonInNetwork(requestContext.getRunAsUser(), requestContext.getNetworkId()).getId();
        RequestContext otherContext = new RequestContext(requestContext.getNetworkId(), otherPerson);

        // Alter current engine date
        Calendar createdCal = Calendar.getInstance();
        createdCal.set(Calendar.MILLISECOND, 0);
        Clock actiClock = activitiProcessEngine.getProcessEngineConfiguration().getClock();
        actiClock.setCurrentTime(createdCal.getTime());
           
        ProcessInstance processInstance = startAdhocProcess(requestContext.getRunAsUser(), requestContext.getNetworkId(), null);
        
        try
        {
            final Task task = activitiProcessEngine.getTaskService().createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
            assertNotNull(task);
            
            // Set some task-properties not set by process-definition
            Calendar dueDateCal = Calendar.getInstance();
            dueDateCal.set(Calendar.MILLISECOND, 0);
            dueDateCal.add(Calendar.DAY_OF_YEAR, 1);
            Date dueDate = dueDateCal.getTime();
            
            task.setDescription("This is a test description");
            task.setAssignee(requestContext.getRunAsUser());
            task.setOwner("john");
            task.setDueDate(dueDate);
            activitiProcessEngine.getTaskService().saveTask(task);

            TasksClient tasksClient = publicApiClient.tasksClient();
            
            // Check resulting task
            JsonNode taskJSONObject = tasksClient.findTaskById(task.getId());
            assertNotNull(taskJSONObject);
            assertEquals(task.getId(), taskJSONObject.get("id").textValue());
            assertEquals(processInstance.getId(), taskJSONObject.get("processId").textValue());
            assertEquals(processInstance.getProcessDefinitionId(), taskJSONObject.get("processDefinitionId").textValue());
            assertEquals("adhocTask", taskJSONObject.get("activityDefinitionId").textValue());
            assertEquals("Adhoc Task", taskJSONObject.get("name").textValue());
            assertEquals("This is a test description", taskJSONObject.get("description").textValue());
            assertEquals(requestContext.getRunAsUser(), taskJSONObject.get("assignee").textValue());
            assertEquals("john", taskJSONObject.get("owner").textValue());
            assertEquals(dueDate, parseDate(taskJSONObject, "dueAt"));
            assertEquals(createdCal.getTime(), parseDate(taskJSONObject, "startedAt"));
            assertEquals(2L, taskJSONObject.get("priority").longValue());
            assertEquals("wf:adhocTask", taskJSONObject.get("formResourceKey").textValue());
            assertNull(taskJSONObject.get("endedAt"));
            assertNull(taskJSONObject.get("durationInMs"));
            
            // get unclaimed task
            activitiProcessEngine.getTaskService().setAssignee(task.getId(), null);
            taskJSONObject = tasksClient.findTaskById(task.getId());
            assertNotNull(taskJSONObject);
            assertEquals(task.getId(), taskJSONObject.get("id").textValue());
            
            // get delegated task
            activitiProcessEngine.getTaskService().setAssignee(task.getId(), requestContext.getRunAsUser());
            activitiProcessEngine.getTaskService().setOwner(task.getId(), requestContext.getRunAsUser());
            activitiProcessEngine.getTaskService().delegateTask(task.getId(), otherContext.getRunAsUser());
            taskJSONObject = tasksClient.findTaskById(task.getId());
            assertNotNull(taskJSONObject);
            assertEquals(task.getId(), taskJSONObject.get("id").textValue());
            assertEquals(otherContext.getRunAsUser(), taskJSONObject.get("assignee").textValue());
            assertEquals(requestContext.getRunAsUser(), taskJSONObject.get("owner").textValue());
            
            // get resolved task
            activitiProcessEngine.getTaskService().resolveTask(task.getId());
            taskJSONObject = tasksClient.findTaskById(task.getId());
            assertNotNull(taskJSONObject);
            assertEquals(task.getId(), taskJSONObject.get("id").textValue());
            assertEquals(requestContext.getRunAsUser(), taskJSONObject.get("assignee").textValue());
            assertEquals(requestContext.getRunAsUser(), taskJSONObject.get("owner").textValue());
            
            // get completed task
            TenantUtil.runAsUserTenant(new TenantRunAsWork<Void>()
            {
                @Override
                public Void doWork() throws Exception
                {
                    activitiProcessEngine.getTaskService().complete(task.getId());
                    return null;
                }
            }, requestContext.getRunAsUser(), requestContext.getNetworkId());
            taskJSONObject = tasksClient.findTaskById(task.getId());
            assertNotNull(taskJSONObject);
            assertEquals(task.getId(), taskJSONObject.get("id").textValue());
            assertEquals(requestContext.getRunAsUser(), taskJSONObject.get("assignee").textValue());
            assertEquals(requestContext.getRunAsUser(), taskJSONObject.get("owner").textValue());
            assertNotNull(taskJSONObject.get("endedAt"));
            
        }
        finally
        {
            cleanupProcessInstance(processInstance);
        }
    }
    
    @Test
    public void testGetTaskByIdAuthorization() throws Exception
    {
        RequestContext requestContext = initApiClientWithTestUser();
        
        String initiator = getOtherPersonInNetwork(requestContext.getRunAsUser(), requestContext.getNetworkId()).getId();
        
        // Start process by one user and try to access the task as the task assignee instead of the process
        // initiator to see if the assignee is authorized to get the task
        ProcessInstance processInstance = startAdhocProcess(initiator, requestContext.getNetworkId(), null);
        try
        {
            Task task = activitiProcessEngine.getTaskService().createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
            assertNotNull(task);
            TasksClient tasksClient = publicApiClient.tasksClient();
            
            // Try accessing task when NOT involved in the task
            try {
                tasksClient.findTaskById(task.getId());
                fail("Exception expected");
            } catch(PublicApiException expected) {
                assertEquals(HttpStatus.FORBIDDEN.value(), expected.getHttpResponse().getStatusCode());
                assertErrorSummary("Permission was denied", expected.getHttpResponse());
            }
            
            // Set assignee, task should be accessible now
            activitiProcessEngine.getTaskService().setAssignee(task.getId(), requestContext.getRunAsUser());
            JsonNode jsonObject = tasksClient.findTaskById(task.getId());
            assertNotNull(jsonObject);
            
            // Fetching task as admin should be possible
            String tenantAdmin = AuthenticationUtil.getAdminUserName() + "@" + requestContext.getNetworkId();
            publicApiClient.setRequestContext(new RequestContext(TenantUtil.DEFAULT_TENANT, tenantAdmin));
            jsonObject = tasksClient.findTaskById(task.getId());
            assertNotNull(jsonObject);
            
            // Fetching the task as a admin from another tenant shouldn't be possible
            TestNetwork anotherNetwork = getOtherNetwork(requestContext.getNetworkId());
            tenantAdmin = AuthenticationUtil.getAdminUserName() + "@" + anotherNetwork.getId();
            publicApiClient.setRequestContext(new RequestContext(TenantUtil.DEFAULT_TENANT, tenantAdmin));
            try {
                tasksClient.findTaskById(task.getId());
                fail("Exception expected");
            } catch(PublicApiException expected) {
                assertEquals(HttpStatus.FORBIDDEN.value(), expected.getHttpResponse().getStatusCode());
                assertErrorSummary("Permission was denied", expected.getHttpResponse());
            }
        }
        finally
        {
            cleanupProcessInstance(processInstance);
        }
    }
    
    
    @Test
    public void testGetUnexistingTaskById() throws Exception
    {
        initApiClientWithTestUser();
        TasksClient tasksClient = publicApiClient.tasksClient();
        try 
        {
            tasksClient.findTaskById("unexisting");
            fail("Exception expected");
        }
        catch(PublicApiException expected)
        {
            assertEquals(HttpStatus.NOT_FOUND.value(), expected.getHttpResponse().getStatusCode());
            assertErrorSummary("The entity with id: unexisting was not found", expected.getHttpResponse());
        }
    }
    
    @Test
    @SuppressWarnings("unchecked")
    public void testUpdateTask() throws Exception
    {
        RequestContext requestContext = initApiClientWithTestUser();
        
        String otherPerson = getOtherPersonInNetwork(requestContext.getRunAsUser(), requestContext.getNetworkId()).getId();
        RequestContext otherContext = new RequestContext(requestContext.getNetworkId(), otherPerson);
        
        String tenantAdmin = AuthenticationUtil.getAdminUserName() + "@" + requestContext.getNetworkId();
        RequestContext adminContext = new RequestContext(requestContext.getNetworkId(), tenantAdmin);
        
        ProcessInstance processInstance = startAdhocProcess(requestContext.getRunAsUser(), requestContext.getNetworkId(), null);
        
        try 
        {
            Task task = activitiProcessEngine.getTaskService().createTaskQuery().processInstanceId(processInstance.getId()).singleResult();

            TasksClient tasksClient = publicApiClient.tasksClient();

            Calendar dueDateCal = Calendar.getInstance();
            dueDateCal.set(Calendar.MILLISECOND, 0);
            dueDateCal.add(Calendar.DAY_OF_YEAR, 1);
            Date dueDate = dueDateCal.getTime();
            
            ObjectNode taskBody = AlfrescoDefaultObjectMapper.createObjectNode();
            taskBody.put("name", "Updated name");
            taskBody.put("description", "Updated description");
            taskBody.put("dueAt", formatDate(dueDate));
            taskBody.put("priority", 1234);
            taskBody.put("assignee", "john");
            taskBody.put("owner", "james");
            
            List<String> selectedFields = new ArrayList<String>();
            selectedFields.addAll(Arrays.asList(new String[] { "name", "description", "dueAt", "priority", "assignee", "owner"}));
            
            JsonNode result = tasksClient.updateTask(task.getId(), taskBody, selectedFields);
            assertEquals("Updated name", result.get("name").textValue());
            assertEquals("Updated description", result.get("description").textValue());
            assertEquals(1234, result.get("priority").intValue());
            assertEquals("john", result.get("assignee").textValue());
            assertEquals("james", result.get("owner").textValue());
            assertEquals(dueDate, parseDate(result, "dueAt"));
            
            task = activitiProcessEngine.getTaskService().createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
            assertNotNull(task);
            assertEquals("Updated name", task.getName());
            assertEquals("Updated description", task.getDescription());
            assertEquals(1234, task.getPriority());
            assertEquals("john", task.getAssignee());
            assertEquals("james", task.getOwner());
            assertEquals(dueDate, task.getDueDate());
            
            // update owner with admin user id
            taskBody = AlfrescoDefaultObjectMapper.createObjectNode();
            taskBody.put("owner", adminContext.getRunAsUser());
            selectedFields = new ArrayList<String>();
            selectedFields.addAll(Arrays.asList(new String[] { "owner"}));
            
            result = tasksClient.updateTask(task.getId(), taskBody, selectedFields);
            assertEquals(adminContext.getRunAsUser(), result.get("owner").textValue());
            
            // update owner with initiator user id
            taskBody = AlfrescoDefaultObjectMapper.createObjectNode();
            taskBody.put("owner", requestContext.getRunAsUser());
            selectedFields = new ArrayList<String>();
            selectedFields.addAll(Arrays.asList(new String[] { "owner"}));
            
            result = tasksClient.updateTask(task.getId(), taskBody, selectedFields);
            assertEquals(requestContext.getRunAsUser(), result.get("owner").textValue());
            
            // update owner with other user id
            taskBody = AlfrescoDefaultObjectMapper.createObjectNode();
            taskBody.put("owner", otherContext.getRunAsUser());
            selectedFields = new ArrayList<String>();
            selectedFields.addAll(Arrays.asList(new String[] { "owner"}));
            
            result = tasksClient.updateTask(task.getId(), taskBody, selectedFields);
            assertEquals(otherContext.getRunAsUser(), result.get("owner").textValue());
            
            // update due date to date more in the future
            dueDateCal.add(Calendar.DAY_OF_YEAR, 1);
            dueDate = dueDateCal.getTime();

            taskBody = AlfrescoDefaultObjectMapper.createObjectNode();
            taskBody.put("dueAt", formatDate(dueDate));
            
            selectedFields = new ArrayList<String>();
            selectedFields.addAll(Arrays.asList(new String[] { "dueAt" }));
            
            result = tasksClient.updateTask(task.getId(), taskBody, selectedFields);
            assertEquals(dueDate, parseDate(result, "dueAt"));
            
            // update due date to date a day less in the future
            dueDateCal.add(Calendar.DAY_OF_YEAR, -1);
            dueDate = dueDateCal.getTime();

            taskBody = AlfrescoDefaultObjectMapper.createObjectNode();
            taskBody.put("dueAt", formatDate(dueDate));
            
            selectedFields = new ArrayList<String>();
            selectedFields.addAll(Arrays.asList(new String[] { "dueAt" }));
            
            result = tasksClient.updateTask(task.getId(), taskBody, selectedFields);
            assertEquals(dueDate, parseDate(result, "dueAt"));
            
            // update due date to current time
            dueDateCal = Calendar.getInstance();
            dueDateCal.set(Calendar.MILLISECOND, 0);
            dueDate = dueDateCal.getTime();

            taskBody = AlfrescoDefaultObjectMapper.createObjectNode();
            taskBody.put("dueAt", formatDate(dueDate));
            
            selectedFields = new ArrayList<String>();
            selectedFields.addAll(Arrays.asList(new String[] { "dueAt" }));
            
            result = tasksClient.updateTask(task.getId(), taskBody, selectedFields);
            assertEquals(dueDate, parseDate(result, "dueAt"));
            
        }
        finally
        {
            cleanupProcessInstance(processInstance);
        }
    }
    
    @Test
    @SuppressWarnings("unchecked")
    public void testUpdateTaskMnt13276() throws Exception
    {
        RequestContext requestContext = initApiClientWithTestUser();
        String initiatorId = requestContext.getRunAsUser();
        ProcessInfo processInfo = startReviewPooledProcess(requestContext);
        
        // create test users
        final List<TestPerson> persons = transactionHelper.doInTransaction(new RetryingTransactionHelper.RetryingTransactionCallback<List<TestPerson>>()
        {
            @SuppressWarnings("synthetic-access")
            public List<TestPerson> execute() throws Throwable
            {
                ArrayList<TestPerson> persons = new ArrayList<TestPerson>();
                String temp = "_" + System.currentTimeMillis();
                persons.add(currentNetwork.createUser(new PersonInfo("user0", "user0", "user0" + temp, "password", null, "skype", "location", "telephone", "mob", "instant", "google")));
                persons.add(currentNetwork.createUser(new PersonInfo("user1", "user1", "user1" + temp, "password", null, "skype", "location", "telephone", "mob", "instant", "google")));
                persons.add(currentNetwork.createUser(new PersonInfo("user2", "user2", "user2" + temp, "password", null, "skype", "location", "telephone", "mob", "instant", "google")));
                return persons;
            }
        }, false, true);

        final MemberOfSite memberOfSite = currentNetwork.getSiteMemberships(initiatorId).get(0);

        // startReviewPooledProcess() uses initiator's site id and role name for construct bpm_groupAssignee, thus we need appropriate things for created users
        transactionHelper.doInTransaction(new RetryingTransactionHelper.RetryingTransactionCallback<Void>()
        {
            public Void execute() throws Throwable
            {
                TenantUtil.runAsUserTenant(new TenantRunAsWork<Void>()
                {
                    @Override
                    public Void doWork() throws Exception
                    {
                        TestSite initiatorSite = (TestSite) memberOfSite.getSite();
                        initiatorSite.inviteToSite(persons.get(0).getId(), memberOfSite.getRole());
                        initiatorSite.inviteToSite(persons.get(1).getId(), memberOfSite.getRole());
                        // this user wouldn't be in group
                        initiatorSite.inviteToSite(persons.get(2).getId(), SiteRole.SiteConsumer == memberOfSite.getRole() ? SiteRole.SiteCollaborator : SiteRole.SiteConsumer);
                        return null;
                    }
                }, AuthenticationUtil.getAdminUserName(), currentNetwork.getId());
                return null;
            }
        }, false, true);

        try
        {
            Task task = activitiProcessEngine.getTaskService().createTaskQuery().processInstanceId(processInfo.getId()).singleResult();
            TasksClient tasksClient = publicApiClient.tasksClient();

            // Updating the task by user in group
            ObjectNode taskBody = AlfrescoDefaultObjectMapper.createObjectNode();
            taskBody.put("name", "Updated name by user in group");
            List<String> selectedFields = new ArrayList<String>();
            selectedFields.addAll(Arrays.asList(new String[] { "name" }));
            requestContext.setRunAsUser(persons.get(0).getId());
            JsonNode result = tasksClient.updateTask(task.getId(), taskBody, selectedFields);
            assertEquals("Updated name by user in group", result.get("name").textValue());
            task = activitiProcessEngine.getTaskService().createTaskQuery().processInstanceId(processInfo.getId()).singleResult();
            assertNotNull(task);
            assertEquals("Updated name by user in group", task.getName());

            // Updating the task by user not in group
            try
            {
                taskBody.put("name", "Updated name by user not in group");
                requestContext.setRunAsUser(persons.get(2).getId());
                tasksClient.updateTask(task.getId(), taskBody, selectedFields);
                fail("User not from group should not see items.");
            }
            catch (PublicApiException expected)
            {
                assertEquals(HttpStatus.FORBIDDEN.value(), expected.getHttpResponse().getStatusCode());
                assertErrorSummary("Permission was denied", expected.getHttpResponse());
            }
            
            // claim task
            TaskService taskService = activitiProcessEngine.getTaskService();
            task = taskService.createTaskQuery().processInstanceId(processInfo.getId()).singleResult();
            taskService.setAssignee(task.getId(), persons.get(1).getId());
            // Updating by user in group for claimed task by another user
            try
            {
                taskBody = AlfrescoDefaultObjectMapper.createObjectNode();
                taskBody.put("name", "Updated name by user in group for claimed task");
                selectedFields.addAll(Arrays.asList(new String[] { "name" }));
                requestContext.setRunAsUser(persons.get(0).getId());
                result = tasksClient.updateTask(task.getId(), taskBody, selectedFields);
                fail("User from group should not see items for claimed task by another user.");
            }
            catch (PublicApiException expected)
            {
                assertEquals(HttpStatus.FORBIDDEN.value(), expected.getHttpResponse().getStatusCode());
                assertErrorSummary("Permission was denied", expected.getHttpResponse());
            }
        }
        finally
        {
            cleanupProcessInstance(processInfo.getId());
        }
    }

   /* 
    * Test association definition extraction from the dictionary as per MNT-11472.
    *  
    * We are going to test association definition extraction through dictionary, when one is not retrieved in context.
    * Context doesn't contains all type definitions, because it requires entire history extraction of a process that causes performance implications.   
    * Type definition extraction from the dictionary is quite fast and it doesn't affect performance.
    * 
    */
    @Test
    @SuppressWarnings("unchecked")
    public void testAssociationDefinitionExtraction() throws Exception
    {
        RequestContext requestContext = initApiClientWithTestUser();

        // The "wbpm:delegatee" custom association is defined in bpmDelegateeModel.xml. This association has "cm:person" target type.
        final String delegatee = "wbpm_delegatee";
        final String user = requestContext.getRunAsUser();
        final String networkId = requestContext.getNetworkId();
        
        // Get person
        final ActivitiScriptNode person = TenantUtil.runAsUserTenant(new TenantRunAsWork<ActivitiScriptNode>()
        {
            @Override
            public ActivitiScriptNode doWork() throws Exception
            {
                return getPersonNodeRef(user);
            }
        }, user, networkId);
        
        // Start custom process instance
        ProcessInstance processInstance = TenantUtil.runAsUserTenant(new TenantRunAsWork<ProcessInstance>()
        {
            @Override
            public ProcessInstance doWork() throws Exception
            {
                deployProcessDefinition("workflow/testCustomDelegatee.bpmn20.xml");
                String processDefinitionKey = "@" + networkId + "@myProcess";
                Map<String, Object> variables = new HashMap<String, Object>();
                variables.put("bpm_assignee", person);
                variables.put("wf_notifyMe", Boolean.FALSE);
                variables.put(WorkflowConstants.PROP_INITIATOR, person);
                return activitiProcessEngine.getRuntimeService().startProcessInstanceByKey(processDefinitionKey, "myProcessKey", variables);
            }
        }, user, networkId);
        
        // Get task 
        Task activeTask = activitiProcessEngine.getTaskService().createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
        assertNotNull(activeTask);
        try
        {
            TasksClient tasksClient = publicApiClient.tasksClient();

            // Define the "wbpm_delegatee" variable for the taskDefine delegatee user name. POST request will be executed.
            ArrayNode variablesArray = AlfrescoDefaultObjectMapper.createArrayNode();
            ObjectNode variableBody = AlfrescoDefaultObjectMapper.createObjectNode();
            variableBody.put("name", delegatee);
            variableBody.put("value", user);
            variableBody.put("scope", "local");
            variablesArray.add(variableBody);

            try
            {
                // Set variable for the task.
                JsonNode response = tasksClient.createTaskVariables(activeTask.getId(), variablesArray);
                assertNotNull(response);
                JsonNode variable = response.get("entry");
                assertEquals(delegatee, variable.get("name").textValue());
                
                // Check that d:noderef type was returned with appropriate nodeRef Id value. 
                assertEquals("d:noderef", variable.get("type").textValue());
                assertEquals(person.getNodeRef().getId(), variable.get("value").textValue());
                
                // Get process variables. GET request will be executed.
                response = publicApiClient.processesClient().getProcessvariables(processInstance.getId());
                assertNotNull(response);
                assertTrue(delegatee + " variable was not set", AlfrescoDefaultObjectMapper.writeValueAsString(response).contains(delegatee));
                ArrayNode entries = (ArrayNode) response.get("entries");

                // Find "wbpm_delegatee" variable.
                for (JsonNode entry : entries)
                {
                    variable = entry.get("entry");
                    if (variable.get("name").textValue().equals(delegatee))
                    {
                        // Check that type corresponds to the target class. 
                        // It means that assoc type def was retrieved successfully from the dictionary.
                        assertEquals("cm:person", variable.get("type").textValue());
                        // Value should be an actual user name.
                        assertEquals(user, variable.get("value").textValue());
                    }
                }
            }
            catch (PublicApiException ex)
            {
                fail("Unexpected result " + ex);
            }
        }
        finally
        {
            cleanupProcessInstance(processInstance);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testUpdateTaskAuthorization() throws Exception
    {
        RequestContext requestContext = initApiClientWithTestUser();
        String initiator = getOtherPersonInNetwork(requestContext.getRunAsUser(), requestContext.getNetworkId()).getId();
        
        ProcessInstance processInstance = startAdhocProcess(initiator, requestContext.getNetworkId(), null);
        try 
        {
            Task task = activitiProcessEngine.getTaskService().createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
            TasksClient tasksClient = publicApiClient.tasksClient();
            
            // Updating the task when NOT assignee/owner or initiator results in an error
            ObjectNode taskBody = AlfrescoDefaultObjectMapper.createObjectNode();
            taskBody.put("name", "Updated name");
            List<String> selectedFields = new ArrayList<String>();
            selectedFields.addAll(Arrays.asList(new String[] { "name" }));
            try 
            {
                tasksClient.updateTask(task.getId(), taskBody, selectedFields);
                fail("Exception expected");
            }
            catch(PublicApiException expected)
            {
                assertEquals(HttpStatus.FORBIDDEN.value(), expected.getHttpResponse().getStatusCode());
                assertErrorSummary("Permission was denied", expected.getHttpResponse());
            }
            
            // Set assignee to current user, update should succeed
            activitiProcessEngine.getTaskService().setAssignee(task.getId(), requestContext.getRunAsUser());
            taskBody.put("name", "Updated name by assignee");
            
            JsonNode result = tasksClient.updateTask(task.getId(), taskBody, selectedFields);
            assertEquals("Updated name by assignee", result.get("name").textValue());
            task = activitiProcessEngine.getTaskService().createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
            assertNotNull(task);
            assertEquals("Updated name by assignee", task.getName());
            
            // Set owner to current user, update should succeed
            activitiProcessEngine.getTaskService().setAssignee(task.getId(), null);
            activitiProcessEngine.getTaskService().setOwner(task.getId(), requestContext.getRunAsUser());
            taskBody.put("name", "Updated name by owner");
            
            result = tasksClient.updateTask(task.getId(), taskBody, selectedFields);
            assertEquals("Updated name by owner", result.get("name").textValue());
            task = activitiProcessEngine.getTaskService().createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
            assertNotNull(task);
            assertEquals("Updated name by owner", task.getName());
            
            // Update as process initiator
            taskBody.put("name", "Updated name by initiator");
            requestContext.setRunAsUser(initiator);
            result = tasksClient.updateTask(task.getId(), taskBody, selectedFields);
            assertEquals("Updated name by initiator", result.get("name").textValue());
            task = activitiProcessEngine.getTaskService().createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
            assertNotNull(task);
            assertEquals("Updated name by initiator", task.getName());
            
            // Update as administrator
            String tenantAdmin = AuthenticationUtil.getAdminUserName() + "@" + requestContext.getNetworkId();
            publicApiClient.setRequestContext(new RequestContext(TenantUtil.DEFAULT_TENANT, tenantAdmin));
            
            taskBody.put("name", "Updated name by admin");
            result = tasksClient.updateTask(task.getId(), taskBody, selectedFields);
            assertEquals("Updated name by admin", result.get("name").textValue());
            task = activitiProcessEngine.getTaskService().createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
            assertNotNull(task);
            assertEquals("Updated name by admin", task.getName());
        }
        finally
        {
            cleanupProcessInstance(processInstance);
        }
    }
    
    @Test
    @SuppressWarnings("unchecked")
    public void testUpdateUnexistingTask() throws Exception
    {
        initApiClientWithTestUser();
        TasksClient tasksClient = publicApiClient.tasksClient();
        try 
        {
            ObjectNode taskBody = AlfrescoDefaultObjectMapper.createObjectNode();
            taskBody.put("name", "Updated name");
            List<String> selectedFields = new ArrayList<String>();
            selectedFields.addAll(Arrays.asList(new String[] { "name" }));
            
            tasksClient.updateTask("unexisting", taskBody, selectedFields);
            fail("Exception expected");
        }
        catch (PublicApiException expected)
        {
            assertEquals(HttpStatus.NOT_FOUND.value(), expected.getHttpResponse().getStatusCode());
            assertErrorSummary("The entity with id: unexisting was not found", expected.getHttpResponse());
        }
    }
    
    @Test
    public void testUpdateTaskInvalidProperty() throws Exception
    {
        RequestContext requestContext = initApiClientWithTestUser();
        ProcessInstance processInstance = startAdhocProcess(requestContext.getRunAsUser(), requestContext.getNetworkId(), null);
        try
        {
            Task task = activitiProcessEngine.getTaskService().createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
            assertNotNull(task);
            
            TasksClient tasksClient = publicApiClient.tasksClient();
            
            // Try updating an unexisting property
            try 
            {
                JsonNode taskBody = AlfrescoDefaultObjectMapper.createObjectNode();
                List<String> selectedFields = new ArrayList<String>();
                selectedFields.add("unexisting");
                tasksClient.updateTask(task.getId(), taskBody, selectedFields);
                fail("Exception expected");
            }
            catch(PublicApiException expected)
            {
                assertEquals(HttpStatus.BAD_REQUEST.value(), expected.getHttpResponse().getStatusCode());
                assertErrorSummary("The property selected for update does not exist for this resource: unexisting", expected.getHttpResponse());
            }
            
            // Try updating a readonly-property
            try 
            {
                JsonNode taskBody = AlfrescoDefaultObjectMapper.createObjectNode();
                List<String> selectedFields = new ArrayList<String>();
                selectedFields.add("id");
                tasksClient.updateTask(task.getId(), taskBody, selectedFields);
                fail("Exception expected");
            }
            catch(PublicApiException expected)
            {
                assertEquals(HttpStatus.BAD_REQUEST.value(), expected.getHttpResponse().getStatusCode());
                assertErrorSummary("The property selected for update is read-only: id", expected.getHttpResponse());
            }
        }
        finally
        {
            cleanupProcessInstance(processInstance);
        }
    }
    
    @Test
    @SuppressWarnings("unchecked")
    public void testClaimTask() throws Exception
    {
        RequestContext requestContext = initApiClientWithTestUser();
        String initiator = getOtherPersonInNetwork(requestContext.getRunAsUser(), requestContext.getNetworkId()).getId();
        
        ProcessInstance processInstance = startAdhocProcess(initiator, requestContext.getNetworkId(), null);
        try 
        {
            Task task = activitiProcessEngine.getTaskService().createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
            TasksClient tasksClient = publicApiClient.tasksClient();
            
            // Claiming the task when NOT part of candidate-group results in an error
            ObjectNode taskBody = AlfrescoDefaultObjectMapper.createObjectNode();
            taskBody.put("state", "claimed");
            List<String> selectedFields = new ArrayList<String>();
            selectedFields.addAll(Arrays.asList(new String[] { "state", "assignee" }));
            try 
            {
                tasksClient.updateTask(task.getId(), taskBody, selectedFields);
                fail("Exception expected");
            }
            catch(PublicApiException expected)
            {
                assertEquals(HttpStatus.FORBIDDEN.value(), expected.getHttpResponse().getStatusCode());
                assertErrorSummary("Permission was denied", expected.getHttpResponse());
            }
            
            // Set candidate for task, but keep assignee
            List<MemberOfSite> memberships = getTestFixture().getNetwork(requestContext.getNetworkId()).getSiteMemberships(requestContext.getRunAsUser());
            assertTrue(memberships.size() > 0);
            MemberOfSite memberOfSite = memberships.get(0);
            String group = "GROUP_site_" + memberOfSite.getSiteId() + "_" + memberOfSite.getRole().name();
            activitiProcessEngine.getTaskService().addCandidateGroup(task.getId(), group);
            
            // Claiming the task when part of candidate-group but another person has this task assigned results in conflict
            try 
            {
                tasksClient.updateTask(task.getId(), taskBody, selectedFields);
                fail("Exception expected");
            }
            catch(PublicApiException expected)
            {
                assertEquals(HttpStatus.CONFLICT.value(), expected.getHttpResponse().getStatusCode());
                assertErrorSummary("The task is already claimed by another user.", expected.getHttpResponse());
            }
            
            // Claiming the task when part of candidate-group and NO assignee is currenlty set should work
            activitiProcessEngine.getTaskService().setAssignee(task.getId(), null);
            taskBody = AlfrescoDefaultObjectMapper.createObjectNode();
            taskBody.put("state", "claimed");
            JsonNode result = tasksClient.updateTask(task.getId(), taskBody, selectedFields);
            assertNotNull(result);
            assertEquals(requestContext.getRunAsUser(), result.get("assignee").textValue());
            assertEquals(requestContext.getRunAsUser(), activitiProcessEngine.getTaskService().createTaskQuery().taskId(task.getId()).singleResult().getAssignee());
            
            // Re-claiming the same task with the current assignee shouldn't be a problem
            result = tasksClient.updateTask(task.getId(), taskBody, selectedFields);
            assertNotNull(result);
            assertEquals(requestContext.getRunAsUser(), result.get("assignee").textValue());
            assertEquals(requestContext.getRunAsUser(), activitiProcessEngine.getTaskService().createTaskQuery().taskId(task.getId()).singleResult().getAssignee());
            
            // Claiming as a candidateUser should also work
            activitiProcessEngine.getTaskService().setAssignee(task.getId(), null);
            activitiProcessEngine.getTaskService().deleteGroupIdentityLink(task.getId(), group, IdentityLinkType.CANDIDATE);
            activitiProcessEngine.getTaskService().addCandidateUser(task.getId(), requestContext.getRunAsUser());
            result = tasksClient.updateTask(task.getId(), taskBody, selectedFields);
            assertNotNull(result);
            assertEquals(requestContext.getRunAsUser(), result.get("assignee").textValue());
            assertEquals(requestContext.getRunAsUser(), activitiProcessEngine.getTaskService().createTaskQuery().taskId(task.getId()).singleResult().getAssignee());
            
            // Claiming as a task owner should also work
            activitiProcessEngine.getTaskService().setAssignee(task.getId(), null);
            activitiProcessEngine.getTaskService().setOwner(task.getId(), requestContext.getRunAsUser());
            activitiProcessEngine.getTaskService().deleteUserIdentityLink(task.getId(), requestContext.getRunAsUser(), IdentityLinkType.CANDIDATE);
            result = tasksClient.updateTask(task.getId(), taskBody, selectedFields);
            assertNotNull(result);
            assertEquals(requestContext.getRunAsUser(), result.get("assignee").textValue());
            assertEquals(requestContext.getRunAsUser(), activitiProcessEngine.getTaskService().createTaskQuery().taskId(task.getId()).singleResult().getAssignee());
            
            // Claiming as admin should work
            String tenantAdmin = AuthenticationUtil.getAdminUserName() + "@" + requestContext.getNetworkId();
            publicApiClient.setRequestContext(new RequestContext(TenantUtil.DEFAULT_TENANT, tenantAdmin));
            
            activitiProcessEngine.getTaskService().setAssignee(task.getId(), null);
            activitiProcessEngine.getTaskService().deleteUserIdentityLink(task.getId(), requestContext.getRunAsUser(), IdentityLinkType.CANDIDATE);
            result = tasksClient.updateTask(task.getId(), taskBody, selectedFields);
            assertNotNull(result);
            assertEquals(tenantAdmin, result.get("assignee").textValue());
            assertEquals(tenantAdmin, activitiProcessEngine.getTaskService().createTaskQuery().taskId(task.getId()).singleResult().getAssignee());
            
        }
        finally
        {
            cleanupProcessInstance(processInstance);
        }
    }
    
    @Test
    @SuppressWarnings("unchecked")
    public void testUnClaimTask() throws Exception
    {
        RequestContext requestContext = initApiClientWithTestUser();
        String user = requestContext.getRunAsUser();
        String initiator = getOtherPersonInNetwork(requestContext.getRunAsUser(), requestContext.getNetworkId()).getId();
        
        ProcessInstance processInstance = startAdhocProcess(initiator, requestContext.getNetworkId(), null);
        try 
        {
            Task task = activitiProcessEngine.getTaskService().createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
            TasksClient tasksClient = publicApiClient.tasksClient();
            
            // Unclaiming the task when NOT assignee, owner, initiator or admin results in error
            ObjectNode taskBody = AlfrescoDefaultObjectMapper.createObjectNode();
            taskBody.put("state", "unclaimed");
            List<String> selectedFields = new ArrayList<String>();
            selectedFields.addAll(Arrays.asList(new String[] { "state" }));
            try 
            {
                tasksClient.updateTask(task.getId(), taskBody, selectedFields);
                fail("Exception expected");
            }
            catch(PublicApiException expected)
            {
                assertEquals(HttpStatus.FORBIDDEN.value(), expected.getHttpResponse().getStatusCode());
                assertErrorSummary("Permission was denied", expected.getHttpResponse());
            }
            
            // Unclaiming as process initiator
            requestContext.setRunAsUser(initiator);
            activitiProcessEngine.getTaskService().setAssignee(task.getId(), null);
            JsonNode result = tasksClient.updateTask(task.getId(), taskBody, selectedFields);
            assertNull(result.get("assignee"));
            assertNull(activitiProcessEngine.getTaskService().createTaskQuery().taskId(task.getId()).singleResult().getAssignee());
            
            // Unclaiming as assignee
            activitiProcessEngine.getTaskService().setAssignee(task.getId(), user);
            requestContext.setRunAsUser(user);
            assertNotNull(activitiProcessEngine.getTaskService().createTaskQuery().taskId(task.getId()).singleResult().getAssignee());
            result = tasksClient.updateTask(task.getId(), taskBody, selectedFields);
            assertNull(result.get("assignee"));
            assertNull(activitiProcessEngine.getTaskService().createTaskQuery().taskId(task.getId()).singleResult().getAssignee());
            
            // Unclaim as owner
            activitiProcessEngine.getTaskService().setOwner(task.getId(), user);
            activitiProcessEngine.getTaskService().setAssignee(task.getId(), initiator);
            assertNotNull(activitiProcessEngine.getTaskService().createTaskQuery().taskId(task.getId()).singleResult().getAssignee());
            result = tasksClient.updateTask(task.getId(), taskBody, selectedFields);
            assertNull(result.get("assignee"));
            assertNull(activitiProcessEngine.getTaskService().createTaskQuery().taskId(task.getId()).singleResult().getAssignee());
            
            // Unclaim as admin
            String tenantAdmin = AuthenticationUtil.getAdminUserName() + "@" + requestContext.getNetworkId();
            publicApiClient.setRequestContext(new RequestContext(TenantUtil.DEFAULT_TENANT, tenantAdmin));
            
            activitiProcessEngine.getTaskService().setAssignee(task.getId(), initiator);
            activitiProcessEngine.getTaskService().deleteUserIdentityLink(task.getId(), requestContext.getRunAsUser(), IdentityLinkType.CANDIDATE);
            assertNotNull(activitiProcessEngine.getTaskService().createTaskQuery().taskId(task.getId()).singleResult().getAssignee());
            result = tasksClient.updateTask(task.getId(), taskBody, selectedFields);
            assertNull(result.get("assignee"));
            assertNull(activitiProcessEngine.getTaskService().createTaskQuery().taskId(task.getId()).singleResult().getAssignee());
        }
        finally
        {
            cleanupProcessInstance(processInstance);
        }
    }
    
    @Test
    @SuppressWarnings("unchecked")
    public void testCompleteTask() throws Exception
    {
        RequestContext requestContext = initApiClientWithTestUser();
        String user = requestContext.getRunAsUser();
        String initiator = getOtherPersonInNetwork(requestContext.getRunAsUser(), requestContext.getNetworkId()).getId();
        
        ProcessInstance processCompleteAsAssignee = startAdhocProcess(initiator, requestContext.getNetworkId(), null);
        ProcessInstance processCompleteAsOwner = startAdhocProcess(initiator, requestContext.getNetworkId(), null);
        ProcessInstance processCompleteAsInitiator = startAdhocProcess(initiator, requestContext.getNetworkId(), null);
        ProcessInstance processCompleteAsAdmin = startAdhocProcess(initiator, requestContext.getNetworkId(), null);
        ProcessInstance processCompleteWithVariables = startAdhocProcess(initiator, requestContext.getNetworkId(), null);
        try 
        {
            Task asAssigneeTask = activitiProcessEngine.getTaskService().createTaskQuery().processInstanceId(processCompleteAsAssignee.getId()).singleResult();
            Task asOwnerTask = activitiProcessEngine.getTaskService().createTaskQuery().processInstanceId(processCompleteAsOwner.getId()).singleResult();
            Task asInitiatorTask = activitiProcessEngine.getTaskService().createTaskQuery().processInstanceId(processCompleteAsInitiator.getId()).singleResult();
            Task asAdminTask = activitiProcessEngine.getTaskService().createTaskQuery().processInstanceId(processCompleteAsAdmin.getId()).singleResult();
            Task withVariablesTask = activitiProcessEngine.getTaskService().createTaskQuery().processInstanceId(processCompleteWithVariables.getId()).singleResult();
            TasksClient tasksClient = publicApiClient.tasksClient();
            
            // Unclaiming the task when NOT assignee, owner, initiator or admin results in error
            ObjectNode taskBody = AlfrescoDefaultObjectMapper.createObjectNode();
            taskBody.put("state", "completed");
            List<String> selectedFields = new ArrayList<String>();
            selectedFields.addAll(Arrays.asList(new String[] { "state" }));
            try 
            {
                tasksClient.updateTask(asAssigneeTask.getId(), taskBody, selectedFields);
                fail("Exception expected");
            }
            catch(PublicApiException expected)
            {
                assertEquals(HttpStatus.FORBIDDEN.value(), expected.getHttpResponse().getStatusCode());
                assertErrorSummary("Permission was denied", expected.getHttpResponse());
            }
            
            // Completing as assignee initiator
            activitiProcessEngine.getTaskService().setAssignee(asAssigneeTask.getId(), user);
            JsonNode result = tasksClient.updateTask(asAssigneeTask.getId(), taskBody, selectedFields);
            assertEquals("completed", result.get("state").textValue());
            assertNotNull(result.get("endedAt"));
            assertNull(activitiProcessEngine.getTaskService().createTaskQuery().taskId(asAssigneeTask.getId()).singleResult());
            
            // Completing as process initiator
            requestContext.setRunAsUser(initiator);
            activitiProcessEngine.getTaskService().setAssignee(asInitiatorTask.getId(), null);
            result = tasksClient.updateTask(asInitiatorTask.getId(), taskBody, selectedFields);
            assertEquals("completed", result.get("state").textValue());
            assertNotNull(result.get("endedAt"));
            assertNull(activitiProcessEngine.getTaskService().createTaskQuery().taskId(asInitiatorTask.getId()).singleResult());
            
            // Completing as owner
            requestContext.setRunAsUser(user);
            asOwnerTask.setOwner(user);
            activitiProcessEngine.getTaskService().saveTask(asOwnerTask);
            result = tasksClient.updateTask(asOwnerTask.getId(), taskBody, selectedFields);
            assertEquals("completed", result.get("state").textValue());
            assertNotNull(result.get("endedAt"));
            assertNull(activitiProcessEngine.getTaskService().createTaskQuery().taskId(asOwnerTask.getId()).singleResult());
            
            // Complete as admin
            String tenantAdmin = AuthenticationUtil.getAdminUserName() + "@" + requestContext.getNetworkId();
            publicApiClient.setRequestContext(new RequestContext(TenantUtil.DEFAULT_TENANT, tenantAdmin));
            asAdminTask.setOwner(null);
            activitiProcessEngine.getTaskService().saveTask(asAdminTask);
            result = tasksClient.updateTask(asAdminTask.getId(), taskBody, selectedFields);
            assertEquals("completed", result.get("state").textValue());
            assertNotNull(result.get("endedAt"));
            assertNull(activitiProcessEngine.getTaskService().createTaskQuery().taskId(asAdminTask.getId()).singleResult());
            
            // Complete with variables
            requestContext.setRunAsUser(initiator);
            activitiProcessEngine.getTaskService().setAssignee(withVariablesTask.getId(), null);
            
            ArrayNode variablesArray = AlfrescoDefaultObjectMapper.createArrayNode();
            ObjectNode variableBody = AlfrescoDefaultObjectMapper.createObjectNode();
            variableBody.put("name", "newGlobalVariable");
            variableBody.put("value", 1234);
            variableBody.put("scope", "global");
            variablesArray.add(variableBody);
            variableBody = AlfrescoDefaultObjectMapper.createObjectNode();
            variableBody.put("name", "newLocalVariable");
            variableBody.put("value", 5678);
            variableBody.put("scope", "local");
            variablesArray.add(variableBody);
            
            taskBody.put("variables", variablesArray);
            selectedFields.add("variables");
            result = tasksClient.updateTask(withVariablesTask.getId(), taskBody, selectedFields);
            assertEquals("completed", result.get("state").textValue());
            assertNotNull(result.get("endedAt"));
            assertNull(activitiProcessEngine.getTaskService().createTaskQuery().taskId(withVariablesTask.getId()).singleResult());
            HistoricTaskInstance historyTask = activitiProcessEngine.getHistoryService().createHistoricTaskInstanceQuery()
                    .taskId(withVariablesTask.getId())
                    .includeProcessVariables()
                    .includeTaskLocalVariables()
                    .singleResult();
            
            assertEquals(1234, historyTask.getProcessVariables().get("newGlobalVariable"));
            assertEquals(5678, historyTask.getTaskLocalVariables().get("newLocalVariable"));
            assertNotNull("The outcome should not be null for completed task.", historyTask.getTaskLocalVariables().get("bpm_outcome"));
            JsonNode variables = tasksClient.findTaskVariables(withVariablesTask.getId());
            assertNotNull(variables);
            JsonNode list = variables.get("list");
            assertNotNull(list);
            ArrayNode entries = (ArrayNode) list.get("entries");
            assertNotNull(entries);
            
            boolean foundGlobal = false;
            boolean foundLocal = false;
            for (JsonNode entry : entries)
            {
                JsonNode variableObject = entry.get("entry");
                if ("newGlobalVariable".equals(variableObject.get("name").textValue()))
                {
                    assertEquals(1234L, variableObject.get("value").longValue());
                    foundGlobal = true;
                }
                else if ("newLocalVariable".equals(variableObject.get("name").textValue()))
                {
                    assertEquals(5678L, variableObject.get("value").longValue());
                    foundLocal = true;
                }
            }
            
            assertTrue(foundGlobal);
            assertTrue(foundLocal);
        }
        finally
        {
            cleanupProcessInstance(processCompleteAsAssignee, processCompleteAsAdmin, processCompleteAsInitiator, 
                    processCompleteAsOwner, processCompleteWithVariables);
        }
    }
    
    @Test
    @SuppressWarnings("unchecked")
    public void testDelegateTask() throws Exception
    {
        RequestContext requestContext = initApiClientWithTestUser();
        String user = requestContext.getRunAsUser();
        String initiator = getOtherPersonInNetwork(requestContext.getRunAsUser(), requestContext.getNetworkId()).getId();
        
        ProcessInstance processInstance = startAdhocProcess(initiator, requestContext.getNetworkId(), null);
        try 
        {
            Task task = activitiProcessEngine.getTaskService().createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
            TasksClient tasksClient = publicApiClient.tasksClient();
            
            // Delegating as non-assignee/owner/initiator/admin should result in an error
            ObjectNode taskBody = AlfrescoDefaultObjectMapper.createObjectNode();
            taskBody.put("state", "delegated");
            taskBody.put("assignee", initiator);
            List<String> selectedFields = new ArrayList<String>();
            selectedFields.addAll(Arrays.asList(new String[] { "state",  "assignee"}));
            try 
            {
                tasksClient.updateTask(task.getId(), taskBody, selectedFields);
                fail("Exception expected");
            }
            catch(PublicApiException expected)
            {
                assertEquals(HttpStatus.FORBIDDEN.value(), expected.getHttpResponse().getStatusCode());
                assertErrorSummary("Permission was denied", expected.getHttpResponse());
            }
            
            // Delegating (as assignee) and not passing in an asisgnee should result in an error
            activitiProcessEngine.getTaskService().setAssignee(task.getId(), user);
            taskBody = AlfrescoDefaultObjectMapper.createObjectNode();
            taskBody.put("state", "delegated");
            selectedFields = new ArrayList<String>();
            selectedFields.addAll(Arrays.asList(new String[] { "state" }));
            try 
            {
                tasksClient.updateTask(task.getId(), taskBody, selectedFields);
                fail("Exception expected");
            }
            catch(PublicApiException expected)
            {
                assertEquals(HttpStatus.BAD_REQUEST.value(), expected.getHttpResponse().getStatusCode());
                assertErrorSummary("When delegating a task, assignee should be selected and provided in the request.", expected.getHttpResponse());
            }
            
            // Delegating as assignee
            taskBody.put("state", "delegated");
            taskBody.put("assignee", initiator);
            selectedFields = new ArrayList<String>();
            selectedFields.addAll(Arrays.asList(new String[] { "state", "assignee" }));
            assertNull(task.getDelegationState());
            JsonNode result = tasksClient.updateTask(task.getId(), taskBody, selectedFields);
            assertEquals("delegated", result.get("state").textValue());
            assertEquals(initiator, result.get("assignee").textValue());
            assertEquals(requestContext.getRunAsUser(), result.get("owner").textValue());
            task = activitiProcessEngine.getTaskService().createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
            assertEquals(DelegationState.PENDING, task.getDelegationState());
            assertEquals(initiator, task.getAssignee());
            assertEquals(requestContext.getRunAsUser(), task.getOwner());
            
            // Delegating as owner
            task.setDelegationState(null);
            task.setOwner(requestContext.getRunAsUser());
            task.setAssignee(null);
            activitiProcessEngine.getTaskService().saveTask(task);
            result = tasksClient.updateTask(task.getId(), taskBody, selectedFields);
            assertEquals("delegated", result.get("state").textValue());
            assertEquals(initiator, result.get("assignee").textValue());
            assertEquals(requestContext.getRunAsUser(), result.get("owner").textValue());
            task = activitiProcessEngine.getTaskService().createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
            assertEquals(DelegationState.PENDING, task.getDelegationState());
            assertEquals(initiator, task.getAssignee());
            assertEquals(requestContext.getRunAsUser(), task.getOwner());
            
            // Delegating as process initiator
            task.setDelegationState(null);
            task.setOwner(null);
            task.setAssignee(null);
            activitiProcessEngine.getTaskService().saveTask(task);
            requestContext.setRunAsUser(initiator);
            taskBody.put("assignee", user);
            result = tasksClient.updateTask(task.getId(), taskBody, selectedFields);
            assertEquals("delegated", result.get("state").textValue());
            assertEquals(user, result.get("assignee").textValue());
            assertEquals(initiator, result.get("owner").textValue());
            task = activitiProcessEngine.getTaskService().createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
            assertEquals(DelegationState.PENDING, task.getDelegationState());
            assertEquals(user, task.getAssignee());
            assertEquals(initiator, task.getOwner());
            
            // Delegating as administrator
            String tenantAdmin = AuthenticationUtil.getAdminUserName() + "@" + requestContext.getNetworkId();
            task.setDelegationState(null);
            task.setOwner(null);
            task.setAssignee(null);
            activitiProcessEngine.getTaskService().saveTask(task);
            task = activitiProcessEngine.getTaskService().createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
            requestContext.setRunAsUser(tenantAdmin);
            taskBody.put("assignee", user);
            result = tasksClient.updateTask(task.getId(), taskBody, selectedFields);
            assertEquals("delegated", result.get("state").textValue());
            assertEquals(user, result.get("assignee").textValue());
            assertEquals(tenantAdmin, result.get("owner").textValue());
            task = activitiProcessEngine.getTaskService().createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
            assertEquals(DelegationState.PENDING, task.getDelegationState());
            assertEquals(user, task.getAssignee());
            assertEquals(tenantAdmin, task.getOwner());
        }
        finally
        {
            cleanupProcessInstance(processInstance);
        }
    }
    
    @Test
    @SuppressWarnings("unchecked")
    public void testSetOutcome() throws Exception
    {
        RequestContext requestContext = initApiClientWithTestUser();
        ProcessInfo processInf = startReviewPooledProcess(requestContext);
        Task task = activitiProcessEngine.getTaskService().createTaskQuery().processInstanceId(processInf.getId()).singleResult();
        TasksClient tasksClient = publicApiClient.tasksClient();
        activitiProcessEngine.getTaskService().saveTask(task);
        Map<String, String> params = new HashMap<String, String>();
        params.put("select", "state,variables");
        HttpResponse response = tasksClient.update("tasks",
                        task.getId(),
                        null,
                        null,
                        "{\"state\":\"completed\",\"variables\":[{\"name\":\"wf_reviewOutcome\",\"value\":\"Approve\",\"scope\":\"local\"},{\"name\":\"bpm_comment\",\"value\":\"approved by me\",\"scope\":\"local\"}]}",
                        params,
                        "Failed to update task",
                        200);
        HistoricTaskInstance historyTask = activitiProcessEngine.getHistoryService().createHistoricTaskInstanceQuery().taskId(task.getId()).includeProcessVariables().includeTaskLocalVariables().singleResult();
        String outcome = (String) historyTask.getTaskLocalVariables().get("bpm_outcome");
        assertEquals("Approve", outcome);
    }
    
    @Test
    @SuppressWarnings("unchecked")
    public void testResolveTask() throws Exception
    {
        RequestContext requestContext = initApiClientWithTestUser();
        String user = requestContext.getRunAsUser();
        String initiator = getOtherPersonInNetwork(requestContext.getRunAsUser(), requestContext.getNetworkId()).getId();
        
        ProcessInstance processInstance = startAdhocProcess(initiator, requestContext.getNetworkId(), null);
        try 
        {
            Task task = activitiProcessEngine.getTaskService().createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
            TasksClient tasksClient = publicApiClient.tasksClient();
            
            // Resolving as non-assignee/owner/initiator/admin should result in an error
            ObjectNode taskBody = AlfrescoDefaultObjectMapper.createObjectNode();
            taskBody.put("state", "resolved");
            taskBody.put("assignee", initiator);
            List<String> selectedFields = new ArrayList<String>();
            selectedFields.addAll(Arrays.asList(new String[] { "state",  "assignee"}));
            try 
            {
                tasksClient.updateTask(task.getId(), taskBody, selectedFields);
                fail("Exception expected");
            }
            catch(PublicApiException expected)
            {
                assertEquals(HttpStatus.FORBIDDEN.value(), expected.getHttpResponse().getStatusCode());
                assertErrorSummary("Permission was denied", expected.getHttpResponse());
            }
            
            // Resolving as assignee
            task.delegate(user);
            activitiProcessEngine.getTaskService().saveTask(task);
            taskBody.put("state", "resolved");
            taskBody.put("assignee", initiator);
            selectedFields = new ArrayList<String>();
            selectedFields.addAll(Arrays.asList(new String[] { "state", "assignee" }));
            JsonNode result = tasksClient.updateTask(task.getId(), taskBody, selectedFields);
            assertEquals("resolved", result.get("state").textValue());
            assertEquals(initiator, result.get("assignee").textValue());
            task = activitiProcessEngine.getTaskService().createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
            assertEquals(DelegationState.RESOLVED, task.getDelegationState());
            assertEquals(initiator, task.getAssignee());
            HistoricTaskInstance historyTask = activitiProcessEngine.getHistoryService().createHistoricTaskInstanceQuery()
                     .taskId(task.getId())
                     .includeProcessVariables()
                     .includeTaskLocalVariables()
                     .singleResult();
            assertNotNull("The outcome should not be null for resolved task.", historyTask.getTaskLocalVariables().get("bpm_outcome"));
            
            // Resolving as owner
            task.setDelegationState(null);
            task.setOwner(requestContext.getRunAsUser());
            task.setAssignee(null);
            activitiProcessEngine.getTaskService().saveTask(task);
            result = tasksClient.updateTask(task.getId(), taskBody, selectedFields);
            assertEquals("resolved", result.get("state").textValue());
            assertEquals(user, result.get("assignee").textValue());
            task = activitiProcessEngine.getTaskService().createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
            assertEquals(DelegationState.RESOLVED, task.getDelegationState());
            assertEquals(user, task.getAssignee());
            
            // Resolving as process initiator
            task.setDelegationState(null);
            task.setOwner(null);
            task.setAssignee(null);
            activitiProcessEngine.getTaskService().saveTask(task);
            requestContext.setRunAsUser(initiator);
            taskBody.put("assignee", user);
            result = tasksClient.updateTask(task.getId(), taskBody, selectedFields);
            assertEquals("resolved", result.get("state").textValue());
            task = activitiProcessEngine.getTaskService().createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
            assertEquals(DelegationState.RESOLVED, task.getDelegationState());
            
            // Resolving as administrator
            String tenantAdmin = AuthenticationUtil.getAdminUserName() + "@" + requestContext.getNetworkId();
            task.setDelegationState(null);
            task.setOwner(initiator);
            task.setAssignee(null);
            activitiProcessEngine.getTaskService().saveTask(task);
            task = activitiProcessEngine.getTaskService().createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
            requestContext.setRunAsUser(tenantAdmin);
            taskBody.put("assignee", user);
            result = tasksClient.updateTask(task.getId(), taskBody, selectedFields);
            assertEquals("resolved", result.get("state").textValue());
            assertEquals(initiator, result.get("assignee").textValue());
            task = activitiProcessEngine.getTaskService().createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
            assertEquals(DelegationState.RESOLVED, task.getDelegationState());
            assertEquals(initiator, task.getAssignee());
        }
        finally
        {
            cleanupProcessInstance(processInstance);
        }
    }
    
    @Test
    @SuppressWarnings("unchecked")
    public void testTaskStateTransitions() throws Exception
    {
        RequestContext requestContext = initApiClientWithTestUser();
        
        ProcessInfo processInstance = startAdhocProcess(requestContext, null);
        try 
        {
            Task task = activitiProcessEngine.getTaskService()
                    .createTaskQuery()
                    .processInstanceId(processInstance.getId())
                    .singleResult();
            
            TasksClient tasksClient = publicApiClient.tasksClient();
            
            // Unclaimed to claimed
            ObjectNode taskBody = AlfrescoDefaultObjectMapper.createObjectNode();
            taskBody.put("state", "claimed");
            List<String> selectedFields = new ArrayList<String>();
            selectedFields.addAll(Arrays.asList(new String[] { "state", "assignee" }));
            JsonNode result = tasksClient.updateTask(task.getId(), taskBody, selectedFields);
            assertEquals("claimed", result.get("state").textValue());
            
            // claimed to unclaimed
            taskBody = AlfrescoDefaultObjectMapper.createObjectNode();
            taskBody.put("state", "unclaimed");
            result = tasksClient.updateTask(task.getId(), taskBody, selectedFields);
            assertEquals("unclaimed", result.get("state").textValue());
        }
        finally
        {
            cleanupProcessInstance(processInstance.getId());
        }
    }
    
    @Test
    @SuppressWarnings("unchecked")
    public void testChangeDueDate() throws Exception
    {
        RequestContext requestContext = initApiClientWithTestUser();
        ProcessInstance processInstance = startAdhocProcess(requestContext.getRunAsUser(), requestContext.getNetworkId(), null);
        try 
        {
            Task task = activitiProcessEngine.getTaskService().createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
            
            TasksClient tasksClient = publicApiClient.tasksClient();
            JsonNode taskObject = tasksClient.findTaskById(task.getId());
            assertNull(taskObject.get("dueAt"));
            
            List<String> selectedFields = new ArrayList<String>();
            selectedFields.addAll(Arrays.asList(new String[] { "name", "description", "dueAt", "priority", "assignee", "owner"}));
            
            // set due date
            ObjectNode taskBody = AlfrescoDefaultObjectMapper.createObjectNode();
            String dueAt = formatDate(new Date());
            taskBody.put("dueAt", dueAt);
            JsonNode result = tasksClient.updateTask(task.getId(), taskBody, selectedFields);
            assertNotNull(result.get("dueAt"));
            
            taskObject = tasksClient.findTaskById(task.getId());
            assertNotNull(taskObject.get("dueAt"));
            
            taskBody = AlfrescoDefaultObjectMapper.createObjectNode();
            taskBody.put("dueAt", taskObject.get("dueAt"));
            result = tasksClient.updateTask(task.getId(), taskBody, selectedFields);
            assertNotNull(result.get("dueAt"));
            
            taskObject = tasksClient.findTaskById(task.getId());
            assertNotNull(taskObject.get("dueAt"));

            ObjectNode variableBody = AlfrescoDefaultObjectMapper.createObjectNode();
            variableBody.put("name", "bpm_workflowDueDate");
            variableBody.put("value", formatDate(new Date()));
            variableBody.put("type", "d:date");
            variableBody.put("scope", "global");
            
            tasksClient.updateTaskVariable(task.getId(), "bpm_workflowDueDate", variableBody);
        }
        finally
        {
            cleanupProcessInstance(processInstance);
        }
    }
    
    @Test
    @SuppressWarnings("unchecked")
    public void testGetTasks() throws Exception
    {
        // Alter current engine date
        Calendar createdCal = Calendar.getInstance();
        createdCal.set(Calendar.MILLISECOND, 0);
        Clock actiClock = activitiProcessEngine.getProcessEngineConfiguration().getClock();
        actiClock.setCurrentTime(createdCal.getTime());
        
        RequestContext requestContext = initApiClientWithTestUser();
        
        String otherPerson = getOtherPersonInNetwork(requestContext.getRunAsUser(), requestContext.getNetworkId()).getId();
        RequestContext otherContext = new RequestContext(requestContext.getNetworkId(), otherPerson);
        
        ProcessInstance processInstance = startAdhocProcess(requestContext.getRunAsUser(), requestContext.getNetworkId(), null);
        try
        {
            Task task = activitiProcessEngine.getTaskService().createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
            assertNotNull(task);
            
            // Set some task-properties not set by process-definition
            Calendar dueDateCal = Calendar.getInstance();
            dueDateCal.set(Calendar.MILLISECOND, 0);
            dueDateCal.add(Calendar.DAY_OF_YEAR, 1);
            Date dueDate = dueDateCal.getTime();
            
            task.setDescription("This is a test description");
            task.setAssignee(requestContext.getRunAsUser());
            task.setOwner("john");
            task.setDueDate(dueDate);
            activitiProcessEngine.getTaskService().saveTask(task);

            TasksClient tasksClient = publicApiClient.tasksClient();
            
            // Check resulting task
            JsonNode taskListJSONObject = tasksClient.findTasks(null);
            assertNotNull(taskListJSONObject);
            ArrayNode jsonEntries = (ArrayNode) taskListJSONObject.get("entries");
            assertEquals(1, jsonEntries.size());
            
            JsonNode taskJSONObject = (jsonEntries.get(0)).get("entry");
            assertNotNull(taskJSONObject);
            assertEquals(task.getId(), taskJSONObject.get("id").textValue());
            assertEquals(processInstance.getId(), taskJSONObject.get("processId").textValue());
            assertEquals(processInstance.getProcessDefinitionId(), taskJSONObject.get("processDefinitionId").textValue());
            assertEquals("adhocTask", taskJSONObject.get("activityDefinitionId").textValue());
            assertEquals("Adhoc Task", taskJSONObject.get("name").textValue());
            assertEquals("This is a test description", taskJSONObject.get("description").textValue());
            assertEquals(requestContext.getRunAsUser(), taskJSONObject.get("assignee").textValue());
            assertEquals("john", taskJSONObject.get("owner").textValue());
            assertEquals(dueDate, parseDate(taskJSONObject, "dueAt"));
            // experiment
            assertEquals(createdCal.getTime().toString(), parseDate(taskJSONObject, "startedAt").toString());
            assertEquals(createdCal.getTime(), parseDate(taskJSONObject, "startedAt"));
            assertEquals(2L, taskJSONObject.get("priority").longValue());
            assertEquals("wf:adhocTask", taskJSONObject.get("formResourceKey").textValue());
            assertNull(taskJSONObject.get("endedAt"));
            assertNull(taskJSONObject.get("durationInMs"));
            
            // get tasks with user that has no assigned tasks
            publicApiClient.setRequestContext(otherContext);
            taskListJSONObject = tasksClient.findTasks(null);
            assertNotNull(taskListJSONObject);
            jsonEntries = (ArrayNode) taskListJSONObject.get("entries");
            assertEquals(0, jsonEntries.size());
            
            // get tasks for user which has one task assigned by somebody else
            publicApiClient.setRequestContext(requestContext);
            ObjectNode taskBody = AlfrescoDefaultObjectMapper.createObjectNode();
            taskBody.put("assignee", otherContext.getRunAsUser());
            
            List<String> selectedFields = new ArrayList<String>();
            selectedFields.addAll(Arrays.asList(new String[] { "assignee" }));
            
            tasksClient.updateTask(task.getId(), taskBody, selectedFields);
            
            publicApiClient.setRequestContext(otherContext);
            taskListJSONObject = tasksClient.findTasks(null);
            assertNotNull(taskListJSONObject);
            jsonEntries = (ArrayNode) taskListJSONObject.get("entries");
            assertEquals(1, jsonEntries.size());
        }
        finally
        {
            cleanupProcessInstance(processInstance);
        }
    }
    
    @Test
    public void testGetTasksWithParams() throws Exception
    {
        RequestContext requestContext = initApiClientWithTestUser();
        
        String otherPerson = getOtherPersonInNetwork(requestContext.getRunAsUser(), requestContext.getNetworkId()).getId();
        RequestContext otherContext = new RequestContext(requestContext.getNetworkId(), otherPerson);
        
        Calendar taskCreated = Calendar.getInstance();
        taskCreated.set(Calendar.MILLISECOND, 0);
        String businessKey = UUID.randomUUID().toString();
        ProcessInstance processInstance = startAdhocProcess(requestContext.getRunAsUser(), requestContext.getNetworkId(), businessKey);
        
        ProcessInfo otherInstance = startReviewPooledProcess(otherContext);
        
        try
        {
            // Complete the adhoc task
            final Task completedTask = activitiProcessEngine.getTaskService().createTaskQuery()
                .processInstanceId(processInstance.getId()).singleResult();
            
            assertNotNull(completedTask);
            
            // Find the review pooled task
            final Task reviewPooledTask = activitiProcessEngine.getTaskService().createTaskQuery()
                .processInstanceId(otherInstance.getId()).singleResult();
            
            assertNotNull(reviewPooledTask);
            // MNT-12221 case, due date should be set as task property, not as variable!!!
            assertNotNull("Due date was not set for review pooled task.", reviewPooledTask.getDueDate());
            
            String anotherUserId = UUID.randomUUID().toString();
            
            Calendar completedTaskDue = Calendar.getInstance();
            completedTaskDue.add(Calendar.HOUR, 1);
            completedTaskDue.set(Calendar.MILLISECOND, 0);
            completedTask.setOwner(requestContext.getRunAsUser());
            completedTask.setPriority(3);
            completedTask.setDueDate(completedTaskDue.getTime());
            completedTask.setAssignee(anotherUserId);
            completedTask.setName("Another task name");
            completedTask.setDescription("This is another test description");
            activitiProcessEngine.getTaskService().saveTask(completedTask);
            
            // Complete task in correct tenant
            TenantUtil.runAsUserTenant(new TenantRunAsWork<Void>()
            {
                @Override
                public Void doWork() throws Exception
                {
                    activitiProcessEngine.getTaskService().complete(completedTask.getId());
                    return null;
                }
            }, requestContext.getRunAsUser(), requestContext.getNetworkId());
            
            // Active task is the second task in the adhoc-process (Verify task completed)
            Task activeTask = activitiProcessEngine.getTaskService().createTaskQuery()
                .processInstanceId(processInstance.getId()).singleResult();
            assertNotNull(activeTask);
        
            Calendar activeTaskDue = Calendar.getInstance();

            activeTaskDue.add(Calendar.HOUR, 2);
            activeTaskDue.set(Calendar.MILLISECOND, 0);
            activeTask.setDueDate(activeTaskDue.getTime());
            activeTask.setName("Task name");
            activeTask.setDescription("This is a test description");
            activeTask.setOwner(requestContext.getRunAsUser());
            activeTask.setPriority(2);
            activeTask.setAssignee(requestContext.getRunAsUser());
            activitiProcessEngine.getTaskService().saveTask(activeTask);
            activitiProcessEngine.getTaskService().addCandidateUser(activeTask.getId(), anotherUserId);
            activitiProcessEngine.getTaskService().addCandidateGroup(activeTask.getId(), "sales");
            activitiProcessEngine.getTaskService().setVariableLocal(activeTask.getId(), "numberVar", 10);
            
            final Task otherTask = activitiProcessEngine.getTaskService().createTaskQuery()
                    .processInstanceId(otherInstance.getId()).singleResult();
           
            TasksClient tasksClient = publicApiClient.tasksClient();
            
            // Test status filtering - active
            Map<String, String> params = new HashMap<String, String>();
            params.put("where", "(status = 'active' AND processId = '" + processInstance.getId() + "')");
            assertTasksPresentInTaskQuery(params, tasksClient, false, activeTask.getId());
            
            // Test status filtering - completed
            params.clear();
            params.put("where", "(status = 'completed' AND processId = '" + processInstance.getId() + "')");
            params.put("orderBy", "dueAt DESC");
            assertTasksPresentInTaskQuery(params, tasksClient, false, completedTask.getId());
            
            // Test status filtering - any
            params.clear();
            params.put("where", "(status = 'any' AND processId = '" + processInstance.getId() + "')");
            assertTasksPresentInTaskQuery(params, tasksClient, false, activeTask.getId(), completedTask.getId());
            
            // Test status filtering - no value should default to 'active'
            params.clear();
            params.put("where", "(processId = '" + processInstance.getId() + "')");
            assertTasksPresentInTaskQuery(params, tasksClient, false, activeTask.getId());
            
            // Test status filtering - illegal status
            params.clear();
            params.put("where", "(status = 'alfrescorocks')");
            try
            {
                tasksClient.findTasks(params);
                fail("Exception expected");
            }
            catch(PublicApiException expected)
            {
                assertEquals(HttpStatus.BAD_REQUEST.value(), expected.getHttpResponse().getStatusCode());
                assertErrorSummary("Invalid status parameter: alfrescorocks", expected.getHttpResponse());
            }
            
            // Next, we test all filtering for active, complete and any tasks
            
            // Assignee filtering
            params.clear();
            params.put("where", "(status = 'active' AND assignee = '" + requestContext.getRunAsUser() + "')");
            assertTasksPresentInTaskQuery(params, tasksClient, activeTask.getId());
            
            params.put("where", "(status = 'completed' AND assignee = '" + anotherUserId + "')");
            params.put("orderBy", "endedAt");
            assertTasksPresentInTaskQuery(params, tasksClient, completedTask.getId());
            
            params.put("where", "(status = 'any' AND assignee = '" + anotherUserId + "')");
            assertTasksPresentInTaskQuery(params, tasksClient, completedTask.getId());
            
            // Owner filtering
            params.clear();
            params.put("where", "(status = 'active' AND owner = '" + requestContext.getRunAsUser() + "')");
            assertTasksPresentInTaskQuery(params, tasksClient, activeTask.getId());
            
            params.put("where", "(status = 'completed' AND owner = '" + requestContext.getRunAsUser() + "')");
            assertTasksPresentInTaskQuery(params, tasksClient, completedTask.getId());
            
            params.put("where", "(status = 'any' AND owner = '" + requestContext.getRunAsUser() + "')");
            assertTasksPresentInTaskQuery(params, tasksClient, activeTask.getId(), completedTask.getId());
            
            // Candidate user filtering, only available for active tasks. When used with completed/any 400 is returned
            params.clear();
            params.put("where", "(status = 'active' AND candidateUser = '" + anotherUserId + "')");
            // No tasks expected since assignee is set
            assertEquals(0L, getResultSizeForTaskQuery(params, tasksClient));
            
            // Clear assignee
            activitiProcessEngine.getTaskService().setAssignee(activeTask.getId(), null);
            assertTasksPresentInTaskQuery(params, tasksClient, activeTask.getId());
            
            // Candidate user with candidate group
            params.clear();
            params.put("where", "(status = 'active' AND candidateUser = '" + otherContext.getRunAsUser() + "')");
            // No tasks expected since assignee is set
            assertEquals(1L, getResultSizeForTaskQuery(params, tasksClient));
            
            params.clear();
            params.put("where", "(status = 'completed' AND candidateUser = '" + anotherUserId + "')");
            try
            {
                tasksClient.findTasks(params);
                fail("Exception expected");
            }
            catch(PublicApiException expected)
            {
                assertEquals(HttpStatus.BAD_REQUEST.value(), expected.getHttpResponse().getStatusCode());
                assertErrorSummary("Filtering on candidateUser is only allowed in combination with status-parameter 'active'", expected.getHttpResponse());
            }
            
            params.clear();
            params.put("where", "(status = 'any' AND candidateUser = '" + anotherUserId + "')");
            try
            {
                tasksClient.findTasks(params);
                fail("Exception expected");
            }
            catch(PublicApiException expected)
            {
                assertEquals(HttpStatus.BAD_REQUEST.value(), expected.getHttpResponse().getStatusCode());
                assertErrorSummary("Filtering on candidateUser is only allowed in combination with status-parameter 'active'", expected.getHttpResponse());
            }
            
            // Candidate group filtering, only available for active tasks. When used with completed/any 400 is returned
            params.clear();
            params.put("where", "(status = 'active' AND candidateGroup = 'sales' AND processId='" + processInstance.getId() +"')");
            assertTasksPresentInTaskQuery(params, tasksClient, activeTask.getId());
            
            params.clear();
            params.put("where", "(status = 'completed' AND candidateGroup = 'sales' AND processId='" + processInstance.getId() +"')");
            try
            {
                tasksClient.findTasks(params);
                fail("Exception expected");
            }
            catch (PublicApiException expected)
            {
                assertEquals(HttpStatus.BAD_REQUEST.value(), expected.getHttpResponse().getStatusCode());
                assertErrorSummary("Filtering on candidateGroup is only allowed in combination with status-parameter 'active'", expected.getHttpResponse());
            }
            
            params.clear();
            params.put("where", "(status = 'any' AND candidateGroup = 'sales' AND processId='" + processInstance.getId() +"')");
            try
            {
                tasksClient.findTasks(params);
                fail("Exception expected");
            }
            catch (PublicApiException expected)
            {
                assertEquals(HttpStatus.BAD_REQUEST.value(), expected.getHttpResponse().getStatusCode());
                assertErrorSummary("Filtering on candidateGroup is only allowed in combination with status-parameter 'active'", expected.getHttpResponse());
            }
            
            // Name filtering
            params.clear();
            params.put("where", "(status = 'active' AND name = 'Task name' AND processId='" + processInstance.getId() +"')");
            assertTasksPresentInTaskQuery(params, tasksClient, activeTask.getId());
            
            params.clear();
            params.put("where", "(status = 'completed' AND name = 'Another task name' AND processId='" + processInstance.getId() +"')");
            assertTasksPresentInTaskQuery(params, tasksClient, completedTask.getId());
            
            params.clear();
            params.put("where", "(status = 'any' AND name = 'Another task name' AND processId='" + processInstance.getId() +"')");
            assertTasksPresentInTaskQuery(params, tasksClient, completedTask.getId());
            
            // Description filtering
            params.clear();
            params.put("where", "(status = 'active' AND description = 'This is a test description' AND processId='" + processInstance.getId() +"')");
            assertTasksPresentInTaskQuery(params, tasksClient, activeTask.getId());
            
            params.clear();
            params.put("where", "(status = 'completed' AND description = 'This is another test description' AND processId='" + processInstance.getId() +"')");
            assertTasksPresentInTaskQuery(params, tasksClient, completedTask.getId());
            
            params.clear();
            params.put("where", "(status = 'any' AND description = 'This is another test description' AND processId='" + processInstance.getId() +"')");
            assertTasksPresentInTaskQuery(params, tasksClient, completedTask.getId());
            
            // Priority filtering
            params.clear();
            params.put("where", "(status = 'active' AND priority = 2 AND processId='" + processInstance.getId() +"')");
            assertTasksPresentInTaskQuery(params, tasksClient, activeTask.getId());
            
            params.put("where", "(status = 'completed' AND priority = 3 AND processId='" + processInstance.getId() +"')");
            assertTasksPresentInTaskQuery(params, tasksClient, completedTask.getId());
            
            params.put("where", "(status = 'any' AND priority = 3 AND processId='" + processInstance.getId() +"')");
            assertTasksPresentInTaskQuery(params, tasksClient, completedTask.getId());
            
            // Process instance business-key filtering
            params.clear();
            params.put("where", "(status = 'active' AND processBusinessKey = '" + businessKey + "')");
            assertTasksPresentInTaskQuery(params, tasksClient, activeTask.getId());
            
            params.clear();
            params.put("where", "(status = 'completed' AND processBusinessKey = '" + businessKey + "')");
            assertTasksPresentInTaskQuery(params, tasksClient, completedTask.getId());
             
            params.clear();
            params.put("where", "(status = 'any' AND processBusinessKey = '" + businessKey + "')");
            assertTasksPresentInTaskQuery(params, tasksClient, completedTask.getId(), activeTask.getId());
            
            params.clear();
            params.put("where", "(status = 'any' AND processBusinessKey MATCHES('" + businessKey + "'))");
            assertTasksPresentInTaskQuery(params, tasksClient, completedTask.getId(), activeTask.getId());
            
            params.clear();
            params.put("where", "(status = 'any' AND processBusinessKey MATCHES('" + businessKey.substring(0, businessKey.length() - 2) + "%'))");
            assertTasksPresentInTaskQuery(params, tasksClient, completedTask.getId(), activeTask.getId());
            
            // Activity definition id filtering
            params.clear();
            params.put("where", "(status = 'active' AND activityDefinitionId = 'verifyTaskDone' AND processId='" + processInstance.getId() +"')");
            assertTasksPresentInTaskQuery(params, tasksClient, activeTask.getId());
            
            params.clear();
            params.put("where", "(status = 'completed' AND activityDefinitionId = 'adhocTask' AND processId='" + processInstance.getId() +"')");
            assertTasksPresentInTaskQuery(params, tasksClient, completedTask.getId());
            
            params.clear();
            params.put("where", "(status = 'any' AND activityDefinitionId = 'adhocTask' AND processId='" + processInstance.getId() +"')");
            assertTasksPresentInTaskQuery(params, tasksClient, completedTask.getId());
            
            // Process definition id filtering
            params.clear();
            params.put("where", "(status = 'active' AND processDefinitionId = '" + processInstance.getProcessDefinitionId() + 
                        "' AND processId='" + processInstance.getId() +"')");
            assertTasksPresentInTaskQuery(params, tasksClient, activeTask.getId());
            
            params.clear();
            params.put("where", "(status = 'completed' AND processDefinitionId = '" + processInstance.getProcessDefinitionId() + 
                        "' AND processId='" + processInstance.getId() +"')");
            assertTasksPresentInTaskQuery(params, tasksClient, completedTask.getId());
            
            params.clear();
            params.put("where", "(status = 'any' AND processDefinitionId = '" + processInstance.getProcessDefinitionId() + 
                        "' AND processId='" + processInstance.getId() +"')");
            assertTasksPresentInTaskQuery(params, tasksClient, activeTask.getId(), completedTask.getId());
            
            // Process definition name filtering 
            params.clear();
            params.put("where", "(status = 'active' AND processDefinitionName = 'Adhoc Activiti Process' AND processId='" + processInstance.getId() +"')");
            assertTasksPresentInTaskQuery(params, tasksClient, activeTask.getId());
            
            params.clear();
            params.put("where", "(status = 'completed' AND processDefinitionName = 'Adhoc Activiti Process' AND processId='" + processInstance.getId() +"')");
            assertTasksPresentInTaskQuery(params, tasksClient, completedTask.getId());
            
            params.clear();
            params.put("where", "(status = 'any' AND processDefinitionName = 'Adhoc Activiti Process' AND processId='" + processInstance.getId() +"')");
            assertTasksPresentInTaskQuery(params, tasksClient, activeTask.getId(), completedTask.getId());
            
            // Due date filtering
            params.clear();
            params.put("where", "(status = 'active' AND dueAt = '" + ISO8601DateFormat.format(activeTaskDue.getTime()) +"')");
            assertTasksPresentInTaskQuery(params, tasksClient, activeTask.getId());
            
            params.clear();
            params.put("where", "(status = 'completed' AND dueAt = '" + ISO8601DateFormat.format(completedTaskDue.getTime()) +"')");
            assertTasksPresentInTaskQuery(params, tasksClient, completedTask.getId());
            
            params.clear();
            params.put("where", "(status = 'any' AND dueAt = '" + ISO8601DateFormat.format(completedTaskDue.getTime()) +"')");
            assertTasksPresentInTaskQuery(params, tasksClient, completedTask.getId());
            
            // Started filtering
            Calendar compareCal = Calendar.getInstance();
            compareCal.set(Calendar.MILLISECOND, 0);
            compareCal.add(Calendar.DAY_OF_YEAR, -1);
            params.clear();
            params.put("where", "(status = 'active' AND startedAt > '" + ISO8601DateFormat.format(compareCal.getTime()) +"')");
            assertTasksPresentInTaskQuery(params, tasksClient, activeTask.getId(), otherTask.getId());
            
            params.clear();
            params.put("where", "(status = 'completed' AND startedAt > '" + ISO8601DateFormat.format(compareCal.getTime()) +"')");
            assertTasksPresentInTaskQuery(params, tasksClient, completedTask.getId());
            
            params.clear();
            params.put("where", "(status = 'any' AND startedAt > '" + ISO8601DateFormat.format(compareCal.getTime()) +"')");
            assertTasksPresentInTaskQuery(params, tasksClient, completedTask.getId(), activeTask.getId(), otherTask.getId());
            
            params.clear();
            params.put("where", "(status = 'any' AND startedAt < '" + ISO8601DateFormat.format(compareCal.getTime()) +"')");
            assertEquals(0, getResultSizeForTaskQuery(params, tasksClient));
            
            params.clear();
            params.put("where", "(variables/local/numberVar > 'd:int 5')");
            assertTasksPresentInTaskQuery(params, tasksClient, activeTask.getId());
            
            params.clear();
            params.put("where", "(variables/local/numberVar > 'd:int 10')");
            assertEquals(0, getResultSizeForTaskQuery(params, tasksClient));
            
            params.clear();
            params.put("where", "(variables/local/numberVar >= 'd_int 10')");
            assertTasksPresentInTaskQuery(params, tasksClient, activeTask.getId());
            
            params.clear();
            params.put("where", "(variables/local/numberVar >= 'd:int 11')");
            assertEquals(0, getResultSizeForTaskQuery(params, tasksClient));
            
            params.clear();
            params.put("where", "(variables/local/numberVar <= 'd:int 10')");
            assertTasksPresentInTaskQuery(params, tasksClient, activeTask.getId());
            
            params.clear();
            params.put("where", "(variables/local/numberVar <= 'd:int 9')");
            assertEquals(0, getResultSizeForTaskQuery(params, tasksClient));
            
            params.clear();
            params.put("where", "(variables/local/numberVar < 'd_int 15')");
            assertTasksPresentInTaskQuery(params, tasksClient, activeTask.getId());
            
            params.clear();
            params.put("where", "(variables/local/numberVar < 'd:int 10')");
            assertEquals(0, getResultSizeForTaskQuery(params, tasksClient));
            
            params.clear();
            params.put("where", "(variables/global/numberVar > 'd:int 5')");
            assertEquals(0, getResultSizeForTaskQuery(params, tasksClient));
            
            params.clear();
            params.put("where", "(variables/numberVar > 'd:int 5')");
            assertEquals(0, getResultSizeForTaskQuery(params, tasksClient));
            
            params.clear();
            params.put("where", "(variables/bpm_dueDate = 'd:datetime " + ISO8601DateFormat.format(new Date()) + "')");
            assertEquals(0, getResultSizeForTaskQuery(params, tasksClient));
            
            params.clear();
            params.put("where", "(variables/bpm_dueDate = 'd:datetime 2013-09-15T12:22:31.866+0000')");
            assertEquals(0, getResultSizeForTaskQuery(params, tasksClient));
            
            params.clear();
            params.put("where", "(variables/bpm_dueDate > 'd:datetime 2013-09-15T12:22:31.866+0000')");
            // MNT-12221 fix, due date is not saved as variable for review pooled workflow, so nothing should be found
            assertEquals(0, getResultSizeForTaskQuery(params, tasksClient));
            
            params.clear();
            params.put("where", "(dueAt = '" + ISO8601DateFormat.format(reviewPooledTask.getDueDate()) + "')");
            assertEquals(1, getResultSizeForTaskQuery(params, tasksClient));
            
            params.clear();
            params.put("where", "(variables/bpm_comment MATCHES ('test%'))");
            assertEquals(0, getResultSizeForTaskQuery(params, tasksClient));
            
            // test with OR operator
            params.clear();
            params.put("where", "(status = 'any' OR candidateGroup = 'sales')");
            try
            {
                tasksClient.findTasks(params);
                fail("Exception expected");
            }
            catch (PublicApiException expected)
            {
                assertEquals(HttpStatus.BAD_REQUEST.value(), expected.getHttpResponse().getStatusCode());
            }

            params.clear();
            params.put("where", "(status = 'completed' AND processDefinitionName = 'Adhoc Activiti Process')");
            JsonNode response = publicApiClient.processesClient().getTasks(processInstance.getId(), params);
            assertNotNull(response);
            JsonNode paginationJSON = response.get("pagination");
            assertEquals(1L, paginationJSON.get("count").longValue());

            params.clear();
            params.put("where", "(status = 'any' AND variables/numberVar < 'd:int 5')");
            assertEquals(0, getResultSizeForTaskQuery(params, tasksClient));

        }
        finally
        {
            cleanupProcessInstance(processInstance.getId(), otherInstance.getId());
        }
    }
    
    @Test
    public void testGetTasksWithPaging() throws Exception 
    {
        RequestContext requestContext = initApiClientWithTestUser();
        // Start 6 processes
        List<ProcessInstance> startedProcesses = new ArrayList<ProcessInstance>();
        try
        {
            int numberOfTasks = 6;
            for (int i = 0; i < numberOfTasks; i++) 
            {
                startedProcesses.add(startAdhocProcess(requestContext.getRunAsUser(), requestContext.getNetworkId(), null));
            }
            
            String processDefinitionId = startedProcesses.get(0).getProcessDefinitionId();
            
            TasksClient tasksClient = publicApiClient.tasksClient();
            
            // Test with existing processDefinitionId
            Map<String, String> params = new HashMap<String, String>();
            params.put("where", "(processDefinitionId = '" + processDefinitionId + "' AND includeProcessVariables = true AND includeTaskVariables = true)");
            JsonNode taskListJSONObject = tasksClient.findTasks(params);
            assertNotNull(taskListJSONObject);
            JsonNode paginationJSON = taskListJSONObject.get("pagination");
            assertEquals(6L, paginationJSON.get("count").longValue());
            assertEquals(6L, paginationJSON.get("totalItems").longValue());
            assertEquals(0L, paginationJSON.get("skipCount").longValue());
            assertEquals(false, paginationJSON.get("hasMoreItems").booleanValue());
            ArrayNode jsonEntries = (ArrayNode) taskListJSONObject.get("entries");
            assertEquals(6, jsonEntries.size());
            validateVariables(jsonEntries.get(0), requestContext);
            
            // Test with existing processDefinitionId and max items
            params.clear();
            params.put("maxItems", "3");
            params.put("where", "(processDefinitionId = '" + processDefinitionId + "' AND includeProcessVariables = true AND includeTaskVariables = true)");
            taskListJSONObject = tasksClient.findTasks(params);
            assertNotNull(taskListJSONObject);
            paginationJSON = taskListJSONObject.get("pagination");
            assertEquals(3L, paginationJSON.get("count").longValue());
            assertEquals(6L, paginationJSON.get("totalItems").longValue());
            assertEquals(0L, paginationJSON.get("skipCount").longValue());
            assertEquals(true, paginationJSON.get("hasMoreItems").booleanValue());
            jsonEntries = (ArrayNode) taskListJSONObject.get("entries");
            assertEquals(3, jsonEntries.size());
            validateVariables(jsonEntries.get(0), requestContext);
            
            // Test with existing processDefinitionId and skip count
            params.clear();
            params.put("skipCount", "2");
            params.put("where", "(processDefinitionId = '" + processDefinitionId + "' AND includeProcessVariables = true AND includeTaskVariables = true)");
            taskListJSONObject = tasksClient.findTasks(params);
            assertNotNull(taskListJSONObject);
            paginationJSON = taskListJSONObject.get("pagination");
            assertEquals(4L, paginationJSON.get("count").longValue());
            assertEquals(6L, paginationJSON.get("totalItems").longValue());
            assertEquals(2L, paginationJSON.get("skipCount").longValue());
            assertEquals(false, paginationJSON.get("hasMoreItems").booleanValue());
            jsonEntries = (ArrayNode) taskListJSONObject.get("entries");
            assertEquals(4, jsonEntries.size());
            
            // Test with existing processDefinitionId and max items and skip count
            params.clear();
            params.put("maxItems", "3");
            params.put("skipCount", "2");
            params.put("where", "(processDefinitionId = '" + processDefinitionId + "' AND includeProcessVariables = true AND includeTaskVariables = true)");
            taskListJSONObject = tasksClient.findTasks(params);
            assertNotNull(taskListJSONObject);
            paginationJSON = taskListJSONObject.get("pagination");
            assertEquals(3L, paginationJSON.get("count").longValue());
            assertEquals(6L, paginationJSON.get("totalItems").longValue());
            assertEquals(2L, paginationJSON.get("skipCount").longValue());
            assertEquals(true, paginationJSON.get("hasMoreItems").booleanValue());
            jsonEntries = (ArrayNode) taskListJSONObject.get("entries");
            assertEquals(3, jsonEntries.size());
            
            // Test with existing processDefinitionId and max items and skip count
            params.clear();
            params.put("maxItems", "3");
            params.put("skipCount", "4");
            params.put("where", "(processDefinitionId = '" + processDefinitionId + "' AND includeProcessVariables = true AND includeTaskVariables = true)");
            taskListJSONObject = tasksClient.findTasks(params);
            assertNotNull(taskListJSONObject);
            paginationJSON = taskListJSONObject.get("pagination");
            assertEquals(2L, paginationJSON.get("count").longValue());
            assertEquals(6L, paginationJSON.get("totalItems").longValue());
            assertEquals(4L, paginationJSON.get("skipCount").longValue());
            assertEquals(false, paginationJSON.get("hasMoreItems").booleanValue());
            jsonEntries = (ArrayNode) taskListJSONObject.get("entries");
            assertEquals(2, jsonEntries.size());
        }
        finally
        {
            cleanupProcessInstance(startedProcesses.toArray(new ProcessInstance[] {}));
        }
    }
    
    @Test
    public void testGetTasksWithPagingAndVariablesLimit() throws Exception 
    {
        RequestContext requestContext = initApiClientWithTestUser();
        List<ProcessInstance> startedProcesses = new ArrayList<ProcessInstance>();
        
        // system.workflow.engine.activiti.taskvariableslimit is set to 200 in test-resources/alfresco-global.properties
        try
        {
            // MNT-16040: Create tasks with a number of variables just below the taskvariableslimit and test that skipCount is working as expected.
            int numberOfTasks = 15;
            for (int i = 0; i < numberOfTasks; i++) 
            {
                startedProcesses.add(startAdhocProcess(requestContext.getRunAsUser(), requestContext.getNetworkId(), null));
            }
            TaskService taskService = activitiProcessEngine.getTaskService();
            List<Task> taskList = new ArrayList<Task>();
            for (int i = 0; i < numberOfTasks; i++) 
            {
                Task task = taskService.createTaskQuery().processInstanceId(startedProcesses.get(i).getProcessInstanceId()).singleResult();
                taskService.setPriority(task.getId(), (i + 1) * 10);
                
                // Add an extra variable to the task, there are other 12 added, so a total of 13 variables for each task.
                taskService.setVariableLocal(task.getId(), "test1", "test1");
                taskList.add(task);
            }
            
            TasksClient tasksClient = publicApiClient.tasksClient();
            
            // Test without skipCount
            Map<String, String> params = new HashMap<String, String>();
            params.put("where", "(includeProcessVariables = true AND includeTaskVariables = true)");
            JsonNode taskListJSONObject = tasksClient.findTasks(params);
            assertNotNull(taskListJSONObject);
            JsonNode paginationJSON = taskListJSONObject.get("pagination");
            assertEquals(15L, paginationJSON.get("count").longValue());
            assertEquals(15L, paginationJSON.get("totalItems").longValue());
            assertEquals(0L, paginationJSON.get("skipCount").longValue());
            assertEquals(false, paginationJSON.get("hasMoreItems").booleanValue());
            ArrayNode jsonEntries = (ArrayNode) taskListJSONObject.get("entries");
            assertEquals(15, jsonEntries.size());
            
            // Test with skipCount
            params.clear();
            params.put("skipCount", "5");
            params.put("where", "(includeProcessVariables = true AND includeTaskVariables = true)");
            taskListJSONObject = tasksClient.findTasks(params);
            assertNotNull(taskListJSONObject);
            paginationJSON = taskListJSONObject.get("pagination");
            assertEquals(10L, paginationJSON.get("count").longValue());
            assertEquals(15L, paginationJSON.get("totalItems").longValue());
            assertEquals(5L, paginationJSON.get("skipCount").longValue());
            assertEquals(false, paginationJSON.get("hasMoreItems").booleanValue());
            jsonEntries = (ArrayNode) taskListJSONObject.get("entries");
            assertEquals(10, jsonEntries.size());
            
            params.clear();
            params.put("maxItems", "10");
            params.put("where", "(includeProcessVariables = true AND includeTaskVariables = true)");
            taskListJSONObject = tasksClient.findTasks(params);
            assertNotNull(taskListJSONObject);
            paginationJSON = taskListJSONObject.get("pagination");
            assertEquals(10L, paginationJSON.get("count").longValue());
            assertEquals(15L, paginationJSON.get("totalItems").longValue());
            assertEquals(0L, paginationJSON.get("skipCount").longValue());
            assertEquals(true, paginationJSON.get("hasMoreItems").booleanValue());
            jsonEntries = (ArrayNode) taskListJSONObject.get("entries");
            assertEquals(10, jsonEntries.size());
        }
        finally
        {
            cleanupProcessInstance(startedProcesses.toArray(new ProcessInstance[] {}));
        }
    }
    
    protected void validateVariables(JsonNode entry, RequestContext requestContext) {
        JsonNode taskObject = entry.get("entry");
        ArrayNode variables = (ArrayNode) taskObject.get("variables");
        boolean foundInitiator = false;
        boolean foundAssignee = false;
        boolean foundPercentageComplete = false;
        boolean foundReassignable = false;
        for (int i = 0; i < variables.size(); i++) 
        {
            JsonNode variableJSON = variables.get(i);
            if ("initiator".equals(variableJSON.get("name").textValue()))
            {
                assertEquals("d:noderef", variableJSON.get("type").textValue());
                assertEquals(requestContext.getRunAsUser(), variableJSON.get("value").textValue());
                foundInitiator = true;
            }
            else if ("bpm_assignee".equals(variableJSON.get("name").textValue()))
            {
                assertEquals("cm:person", variableJSON.get("type").textValue());
                assertEquals(requestContext.getRunAsUser(), variableJSON.get("value").textValue());
                foundAssignee = true;
            }
            else if ("bpm_percentComplete".equals(variableJSON.get("name").textValue()))
            {
                assertEquals("d:int", variableJSON.get("type").textValue());
                assertEquals(0L, variableJSON.get("value").longValue());
                foundPercentageComplete = true;
            }
            else if ("bpm_reassignable".equals(variableJSON.get("name").textValue()))
            {
                assertEquals("d:boolean", variableJSON.get("type").textValue());
                assertEquals(Boolean.TRUE, variableJSON.get("value").booleanValue());
                foundReassignable = true;
            }
        }
        
        assertTrue(foundInitiator);
        assertTrue(foundAssignee); 
        assertTrue(foundPercentageComplete);
        assertTrue(foundReassignable);
    }
    
    @Test
    public void testGetTasksWithSorting() throws Exception
    {
        RequestContext requestContext = initApiClientWithTestUser();
        // Start 6 processes
        List<ProcessInstance> startedProcesses = new ArrayList<ProcessInstance>();
        try
        {
            int numberOfTasks = 6;
            for (int i = 0; i < numberOfTasks; i++) 
            {
                startedProcesses.add(startAdhocProcess(requestContext.getRunAsUser(), requestContext.getNetworkId(), null));
            }
            
            List<Task> taskList = new ArrayList<Task>();
            for (int i = 0; i < numberOfTasks; i++) 
            {
                Task task = activitiProcessEngine.getTaskService().createTaskQuery().processInstanceId(startedProcesses.get(i).getProcessInstanceId()).singleResult();
                activitiProcessEngine.getTaskService().setPriority(task.getId(), (i + 1) * 10);
                taskList.add(task);
            }
            
            // set last task priority to 1
            activitiProcessEngine.getTaskService().setPriority(taskList.get(numberOfTasks - 1).getId(), 1);
            
            TasksClient tasksClient = publicApiClient.tasksClient();
            
            // Test with existing processDefinitionId
            Map<String, String> params = new HashMap<String, String>();
            params.put("where", "(processDefinitionId = '" + startedProcesses.get(0).getProcessDefinitionId() + "')");
            params.put("orderBy", "priority ASC");
            JsonNode tasksResponseJSON = tasksClient.findTasks(params);
            
            JsonNode paginationJSON = tasksResponseJSON.get("pagination");
            assertEquals(6L, paginationJSON.get("count").longValue());
            assertEquals(6L, paginationJSON.get("totalItems").longValue());
            ArrayNode tasksListJSON = (ArrayNode) tasksResponseJSON.get("entries");
            assertEquals(6, tasksListJSON.size());
            JsonNode taskJSON = (tasksListJSON.get(0)).get("entry");
            assertEquals(taskList.get(numberOfTasks - 1).getId(), taskJSON.get("id").textValue());
            taskJSON = (tasksListJSON.get(1)).get("entry");
            assertEquals(taskList.get(0).getId(), taskJSON.get("id").textValue());
            taskJSON = (tasksListJSON.get(2)).get("entry");
            assertEquals(taskList.get(1).getId(), taskJSON.get("id").textValue());
            taskJSON = (tasksListJSON.get(3)).get("entry");
            assertEquals(taskList.get(2).getId(), taskJSON.get("id").textValue());
            taskJSON = (tasksListJSON.get(4)).get("entry");
            assertEquals(taskList.get(3).getId(), taskJSON.get("id").textValue());
            taskJSON = (tasksListJSON.get(5)).get("entry");
            assertEquals(taskList.get(4).getId(), taskJSON.get("id").textValue());
            
            params.put("orderBy", "priority DESC");
            tasksResponseJSON = tasksClient.findTasks(params);
            
            paginationJSON = tasksResponseJSON.get("pagination");
            assertEquals(6L, paginationJSON.get("count").longValue());
            assertEquals(6L, paginationJSON.get("totalItems").longValue());
            tasksListJSON = (ArrayNode) tasksResponseJSON.get("entries");
            assertEquals(6, tasksListJSON.size());
            taskJSON = (tasksListJSON.get(0)).get("entry");
            assertEquals(taskList.get(4).getId(), taskJSON.get("id").textValue());
            taskJSON = (tasksListJSON.get(1)).get("entry");
            assertEquals(taskList.get(3).getId(), taskJSON.get("id").textValue());
            taskJSON = (tasksListJSON.get(2)).get("entry");
            assertEquals(taskList.get(2).getId(), taskJSON.get("id").textValue());
            taskJSON = (tasksListJSON.get(3)).get("entry");
            assertEquals(taskList.get(1).getId(), taskJSON.get("id").textValue());
            taskJSON = (tasksListJSON.get(4)).get("entry");
            assertEquals(taskList.get(0).getId(), taskJSON.get("id").textValue());
            taskJSON = (tasksListJSON.get(5)).get("entry");
            assertEquals(taskList.get(numberOfTasks - 1).getId(), taskJSON.get("id").textValue());

            params.put("orderBy", "dueAt DESC");
            tasksResponseJSON = tasksClient.findTasks(params);
            tasksListJSON = (ArrayNode) tasksResponseJSON.get("entries");
            assertEquals(6, tasksListJSON.size());

        }
        finally
        {
            cleanupProcessInstance(startedProcesses.toArray(new ProcessInstance[] {}));
        }
    }
    
    @Test
    public void testGetTasksAuthorization() throws Exception
    {
        RequestContext requestContext = initApiClientWithTestUser();
        String initiator = getOtherPersonInNetwork(requestContext.getRunAsUser(), requestContext.getNetworkId()).getId();
        
        // Start process by one user and try to access the task as the task assignee instead of the process
        // initiator to see if the assignee is authorized to get the task
        ProcessInstance processInstance = startAdhocProcess(initiator, requestContext.getNetworkId(), null);
        try
        {
            Task task = activitiProcessEngine.getTaskService().createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
            assertNotNull(task);
            TasksClient tasksClient = publicApiClient.tasksClient();
            
            Map<String, String> params = new HashMap<String, String>();
            params.put("processId", processInstance.getId());
            
            JsonNode resultingTasks = tasksClient.findTasks(params);
            assertNotNull(resultingTasks);
            ArrayNode jsonEntries = (ArrayNode) resultingTasks.get("entries");
            assertNotNull(jsonEntries);
            assertEquals(0, jsonEntries.size());
            
            // Set assignee, task should be in the result now
            activitiProcessEngine.getTaskService().setAssignee(task.getId(), requestContext.getRunAsUser());
            
            resultingTasks = tasksClient.findTasks(params);
            assertNotNull(resultingTasks);
            jsonEntries = (ArrayNode) resultingTasks.get("entries");
            assertNotNull(jsonEntries);
            assertEquals(1, jsonEntries.size());
            
            // Fetching task as admin should be possible
            String tenantAdmin = AuthenticationUtil.getAdminUserName() + "@" + requestContext.getNetworkId();
            publicApiClient.setRequestContext(new RequestContext(TenantUtil.DEFAULT_TENANT, tenantAdmin));
            resultingTasks = tasksClient.findTasks(params);
            assertNotNull(resultingTasks);
            jsonEntries = (ArrayNode) resultingTasks.get("entries");
            assertNotNull(jsonEntries);
            assertEquals(1, jsonEntries.size());
            
            // Fetching the task as a admin from another tenant shouldn't be possible
            TestNetwork anotherNetwork = getOtherNetwork(requestContext.getNetworkId());
            tenantAdmin = AuthenticationUtil.getAdminUserName() + "@" + anotherNetwork.getId();
            publicApiClient.setRequestContext(new RequestContext(TenantUtil.DEFAULT_TENANT, tenantAdmin));
            resultingTasks = tasksClient.findTasks(params);
            assertNotNull(resultingTasks);
            jsonEntries = (ArrayNode) resultingTasks.get("entries");
            assertNotNull(jsonEntries);
            assertEquals(0, jsonEntries.size());
        }
        finally
        {
            cleanupProcessInstance(processInstance);
        }
    }
    
    @Test
    public void testGetTaskCandidates() throws Exception
    {
        RequestContext requestContext = initApiClientWithTestUser();
        ProcessInstance processInstance = startAdhocProcess(requestContext.getRunAsUser(), requestContext.getNetworkId(), null);
        try
        {
            Task task = activitiProcessEngine.getTaskService().createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
            assertNotNull(task);
            activitiProcessEngine.getTaskService().addCandidateUser(task.getId(), "testuser");
            activitiProcessEngine.getTaskService().addCandidateUser(task.getId(), "testuser2");
            activitiProcessEngine.getTaskService().addCandidateGroup(task.getId(), "testgroup");

            TasksClient tasksClient = publicApiClient.tasksClient();
            
            JsonNode taskCandidatesJSONObject = tasksClient.findTaskCandidates(task.getId());
            assertNotNull(taskCandidatesJSONObject);
            ArrayNode candidateArrayJSON = (ArrayNode) (taskCandidatesJSONObject.get("list")).get("entries");
            assertEquals(3, candidateArrayJSON.size());
            
            boolean testUser1Found = false;
            boolean testUser2Found = false;
            boolean testGroupFound = false;
            
            for(int i=0; i < candidateArrayJSON.size(); i++) {
                JsonNode entry = (candidateArrayJSON.get(i)).get("entry");
                if("group".equals(entry.get("candidateType").textValue()))
                {
                    testGroupFound = true;
                    assertEquals("testgroup", entry.get("candidateId").textValue());
                } else if("user".equals(entry.get("candidateType").textValue()))
                {
                    if("testuser".equals(entry.get("candidateId").textValue()))
                    {
                        testUser1Found = true;
                    } 
                    else if("testuser2".equals(entry.get("candidateId").textValue()))
                    {
                        testUser2Found = true;
                    }
                }
            }
            
            assertTrue(testUser1Found);
            assertTrue(testUser2Found);
            assertTrue(testGroupFound);
        }
        finally
        {
            cleanupProcessInstance(processInstance);
        }
    }
    
    @Test
    public void testGetTaskVariablesAuthentication() throws Exception
    {
        RequestContext requestContext = initApiClientWithTestUser();
        
        String initiator = getOtherPersonInNetwork(requestContext.getRunAsUser(), requestContext.getNetworkId()).getId();
        
        // Start process by one user and try to access the task variables as the task assignee instead of the process
        // initiator to see if the assignee is authorized to get the task
        ProcessInstance processInstance = startAdhocProcess(initiator, requestContext.getNetworkId(), null);
        try
        {
            Task task = activitiProcessEngine.getTaskService().createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
            assertNotNull(task);
            TasksClient tasksClient = publicApiClient.tasksClient();
            
            // Try accessing task variables when NOT involved in the task
            try {
                tasksClient.findTaskVariables(task.getId());
                fail("Exception expected");
            } catch(PublicApiException expected) {
                assertEquals(HttpStatus.FORBIDDEN.value(), expected.getHttpResponse().getStatusCode());
                assertErrorSummary("Permission was denied", expected.getHttpResponse());
            }
            
            // Set assignee, task variables should be accessible now
            activitiProcessEngine.getTaskService().setAssignee(task.getId(), requestContext.getRunAsUser());
            JsonNode jsonObject = tasksClient.findTaskVariables(task.getId());
            assertNotNull(jsonObject);
            
            // Fetching task variables as admin should be possible
            String tenantAdmin = AuthenticationUtil.getAdminUserName() + "@" + requestContext.getNetworkId();
            publicApiClient.setRequestContext(new RequestContext(TenantUtil.DEFAULT_TENANT, tenantAdmin));
            jsonObject = tasksClient.findTaskVariables(task.getId());
            assertNotNull(jsonObject);
            
            // Fetching the task variables as a admin from another tenant shouldn't be possible
            TestNetwork anotherNetwork = getOtherNetwork(requestContext.getNetworkId());
            tenantAdmin = AuthenticationUtil.getAdminUserName() + "@" + anotherNetwork.getId();
            publicApiClient.setRequestContext(new RequestContext(TenantUtil.DEFAULT_TENANT, tenantAdmin));
            try {
                tasksClient.findTaskVariables(task.getId());
                fail("Exception expected");
            } catch(PublicApiException expected) {
                assertEquals(HttpStatus.FORBIDDEN.value(), expected.getHttpResponse().getStatusCode());
                assertErrorSummary("Permission was denied", expected.getHttpResponse());
            }
        }
        finally
        {
            cleanupProcessInstance(processInstance);
        }
    }
    
    @Test
    public void testGetTaskVariables() throws Exception
    {
        RequestContext requestContext = initApiClientWithTestUser();

        ProcessInstance processInstance = startAdhocProcess(requestContext.getRunAsUser(), requestContext.getNetworkId(), null);
        try
        {
            Task task = activitiProcessEngine.getTaskService().createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
            assertNotNull(task);
            
            Map<String, Object> actualLocalVariables = activitiProcessEngine.getTaskService().getVariablesLocal(task.getId());
            Map<String, Object> actualGlobalVariables = activitiProcessEngine.getRuntimeService().getVariables(processInstance.getId());
            assertEquals(5, actualGlobalVariables.size());
            assertEquals(8, actualLocalVariables.size());
            
            TasksClient tasksClient = publicApiClient.tasksClient();
            JsonNode variables = tasksClient.findTaskVariables(task.getId());
            assertNotNull(variables);
            JsonNode list = variables.get("list");
            assertNotNull(list);
            ArrayNode entries = (ArrayNode) list.get("entries");
            assertNotNull(entries);
            
            // Check pagination object for size
            JsonNode pagination = list.get("pagination");
            assertNotNull(pagination);
            assertEquals(12L, pagination.get("count").longValue());
            assertEquals(12L, pagination.get("totalItems").longValue());
            assertEquals(0L, pagination.get("skipCount").longValue());
            assertFalse(pagination.get("hasMoreItems").booleanValue());
            
            // Should contain one variable less than the actual variables in the engine, tenant-domain var is filtered out
            assertEquals(actualLocalVariables.size() + actualGlobalVariables.size() - 1, entries.size());
            
            List<JsonNode> localResults = new ArrayList<>();
            List<JsonNode> globalResults = new ArrayList<>();
            
            Set<String> expectedLocalVars = new HashSet<String>();
            expectedLocalVars.addAll(actualLocalVariables.keySet());
            
            Set<String> expectedGlobalVars = new HashSet<String>();
            expectedGlobalVars.addAll(actualGlobalVariables.keySet());
            expectedGlobalVars.remove(ActivitiConstants.VAR_TENANT_DOMAIN);
            
            // Add JSON entries to map for easy access when asserting values
            Map<String, JsonNode> entriesByName = new HashMap<>();
            for(int i=0; i < entries.size(); i++) 
            {
                JsonNode entry = (entries.get(i)).get("entry");
                assertNotNull(entry);
                
                // Check if full entry is present
                assertNotNull(entry.get("scope"));
                assertNotNull(entry.get("name"));
                assertNotNull(entry.get("type"));
                if(!entry.get("name").textValue().equals("bpm_hiddenTransitions")) {
                    assertNotNull(entry.get("value"));
                }
                
                if("local".equals(entry.get("scope").textValue()))
                {
                    localResults.add(entry);
                    expectedLocalVars.remove(entry.get("name").textValue());
                } 
                else if("global".equals(entry.get("scope").textValue()))
                {
                    globalResults.add(entry);
                    expectedGlobalVars.remove(entry.get("name").textValue());
                }
                
                entriesByName.put(entry.get("name").textValue(), entry);
            }
            
            // Check correct count of globas vs. local
            assertEquals(4, globalResults.size());
            assertEquals(8, localResults.size());
            
            // Check if all variables are present
            assertEquals(0, expectedGlobalVars.size());
            assertEquals(0, expectedLocalVars.size());
            
            // Check if types are returned, based on content-model
            JsonNode var = entriesByName.get("bpm_percentComplete");
            assertNotNull(var);
            assertEquals("d:int", var.get("type").textValue());
            assertEquals(0L, var.get("value").longValue());
            
            var = entriesByName.get("bpm_reassignable");
            assertNotNull(var);
            assertEquals("d:boolean", var.get("type").textValue());
            assertEquals(Boolean.TRUE, var.get("value").booleanValue());
            
            var = entriesByName.get("bpm_status");
            assertNotNull(var);
            assertEquals("d:text", var.get("type").textValue());
            assertEquals("Not Yet Started", var.get("value").textValue());
            
            var = entriesByName.get("bpm_assignee");
            assertNotNull(var);
            assertEquals("cm:person", var.get("type").textValue());
            
            final String userName = requestContext.getRunAsUser();
            assertEquals(userName, var.get("value").textValue());
            
        }
        finally
        {
            cleanupProcessInstance(processInstance);
        }
    }
    
    @Test
    @SuppressWarnings("unchecked")
    public void testUpdateTaskVariablesPresentInModel() throws Exception
    {
        RequestContext requestContext = initApiClientWithTestUser();

        ProcessInstance processInstance = startAdhocProcess(requestContext.getRunAsUser(), requestContext.getNetworkId(), null);
        try
        {
            Task task = activitiProcessEngine.getTaskService().createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
            assertNotNull(task);
            
            Map<String, Object> actualLocalVariables = activitiProcessEngine.getTaskService().getVariablesLocal(task.getId());
            Map<String, Object> actualGlobalVariables = activitiProcessEngine.getRuntimeService().getVariables(processInstance.getId());
            assertEquals(5, actualGlobalVariables.size());
            assertEquals(8, actualLocalVariables.size());
            
            // Update a global value that is present in the model with type given
            ObjectNode variableBody = AlfrescoDefaultObjectMapper.createObjectNode();
            variableBody.put("name", "bpm_percentComplete");
            variableBody.put("value", 20);
            variableBody.put("type", "d:int");
            variableBody.put("scope", "global");
            
            TasksClient tasksClient = publicApiClient.tasksClient();
            JsonNode resultEntry = tasksClient.updateTaskVariable(task.getId(), "bpm_percentComplete", variableBody);
            assertNotNull(resultEntry);
            JsonNode result = resultEntry.get("entry");
            assertEquals("bpm_percentComplete", result.get("name").textValue());
            assertEquals(20L, result.get("value").longValue());
            assertEquals("d:int", result.get("type").textValue());
            assertEquals("global", result.get("scope").textValue());
            assertEquals(20, activitiProcessEngine.getRuntimeService().getVariable(processInstance.getId(), "bpm_percentComplete"));
            
            // Update a local value that is present in the model with name and scope, omitting type to see if type is deducted from model
            variableBody = AlfrescoDefaultObjectMapper.createObjectNode();
            variableBody.put("name", "bpm_percentComplete");
            variableBody.put("value", 30);
            variableBody.put("scope", "local");
            
            result = resultEntry = tasksClient.updateTaskVariable(task.getId(), "bpm_percentComplete", variableBody);
            assertNotNull(resultEntry);
            result = resultEntry.get("entry");
            assertEquals("bpm_percentComplete", result.get("name").textValue());
            assertEquals(30L, result.get("value").longValue());
            assertEquals("d:int", result.get("type").textValue());
            assertEquals("local", result.get("scope").textValue());
            assertEquals(30, activitiProcessEngine.getTaskService().getVariable(task.getId(), "bpm_percentComplete"));
            // Global variable should remain unaffected
            assertEquals(20, activitiProcessEngine.getRuntimeService().getVariable(processInstance.getId(), "bpm_percentComplete"));
        }
        finally
        {
            cleanupProcessInstance(processInstance);
        }
    }
    
    @Test
    @SuppressWarnings("unchecked")
    public void testUpdateTaskVariablesExplicitTyped() throws Exception
    {
        RequestContext requestContext = initApiClientWithTestUser();

        ProcessInstance processInstance = startAdhocProcess(requestContext.getRunAsUser(), requestContext.getNetworkId(), null);
        try
        {
            Task task = activitiProcessEngine.getTaskService().createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
            assertNotNull(task);
            
            Map<String, Object> actualLocalVariables = activitiProcessEngine.getTaskService().getVariablesLocal(task.getId());
            Map<String, Object> actualGlobalVariables = activitiProcessEngine.getRuntimeService().getVariables(processInstance.getId());
            assertEquals(5, actualGlobalVariables.size());
            assertEquals(8, actualLocalVariables.size());
            
            // Set a new global value that is NOT present in the model with type given
            ObjectNode variableBody = AlfrescoDefaultObjectMapper.createObjectNode();
            variableBody.put("name", "newVariable");
            variableBody.put("value", 1234L);
            variableBody.put("type", "d:long");
            variableBody.put("scope", "global");
            
            TasksClient tasksClient = publicApiClient.tasksClient();
            JsonNode resultEntry = tasksClient.updateTaskVariable(task.getId(), "newVariable", variableBody);
            assertNotNull(resultEntry);
            JsonNode result = resultEntry.get("entry");
            assertEquals("newVariable", result.get("name").textValue());
            assertEquals(1234L, result.get("value").longValue());
            assertEquals("d:long", result.get("type").textValue());
            assertEquals("global", result.get("scope").textValue());
            assertEquals(1234L, activitiProcessEngine.getRuntimeService().getVariable(processInstance.getId(), "newVariable"));
        }
        finally
        {
            cleanupProcessInstance(processInstance);
        }
    }
    
    @Test
    @SuppressWarnings("unchecked")
    public void testUpdateTaskVariablesNoExplicitType() throws Exception
    {
        RequestContext requestContext = initApiClientWithTestUser();

        ProcessInstance processInstance = startAdhocProcess(requestContext.getRunAsUser(), requestContext.getNetworkId(), null);
        try
        {
            Task task = activitiProcessEngine.getTaskService().createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
            assertNotNull(task);
            
            Map<String, Object> actualLocalVariables = activitiProcessEngine.getTaskService().getVariablesLocal(task.getId());
            Map<String, Object> actualGlobalVariables = activitiProcessEngine.getRuntimeService().getVariables(processInstance.getId());
            assertEquals(5, actualGlobalVariables.size());
            assertEquals(8, actualLocalVariables.size());
            
            // Raw number value
            ObjectNode variableBody = AlfrescoDefaultObjectMapper.createObjectNode();
            variableBody.put("name", "newVariable");
            variableBody.put("value", 1234);
            variableBody.put("scope", "global");
            
            TasksClient tasksClient = publicApiClient.tasksClient();
            JsonNode resultEntry = tasksClient.updateTaskVariable(task.getId(), "newVariable", variableBody);
            assertNotNull(resultEntry);
            JsonNode result = resultEntry.get("entry");
            assertEquals("newVariable", result.get("name").textValue());
            assertEquals(1234L, result.get("value").longValue());
            assertEquals("d:int", result.get("type").textValue());
            assertEquals("global", result.get("scope").textValue());
            assertEquals(1234, activitiProcessEngine.getRuntimeService().getVariable(processInstance.getId(), "newVariable"));
            
            
            // Raw boolean value
            variableBody = AlfrescoDefaultObjectMapper.createObjectNode();
            variableBody.put("name", "newVariable");
            variableBody.put("value", Boolean.TRUE);
            variableBody.put("scope", "local");
            
            resultEntry = tasksClient.updateTaskVariable(task.getId(), "newVariable", variableBody);
            assertNotNull(resultEntry);
            result = resultEntry.get("entry");
            assertEquals("newVariable", result.get("name").textValue());
            assertEquals(Boolean.TRUE, result.get("value").booleanValue());
            assertEquals("d:boolean", result.get("type").textValue());
            assertEquals("local", result.get("scope").textValue());
            assertEquals(Boolean.TRUE, activitiProcessEngine.getTaskService().getVariable(task.getId(), "newVariable"));
            
            
            // Raw string value
            variableBody = AlfrescoDefaultObjectMapper.createObjectNode();
            variableBody.put("name", "newVariable");
            variableBody.put("value", "test value");
            variableBody.put("scope", "global");
            
            resultEntry = tasksClient.updateTaskVariable(task.getId(), "newVariable", variableBody);
            assertNotNull(resultEntry);
            result = resultEntry.get("entry");
            assertEquals("newVariable", result.get("name").textValue());
            assertEquals("test value", result.get("value").textValue());
            assertEquals("d:text", result.get("type").textValue());
            assertEquals("global", result.get("scope").textValue());
            assertEquals("test value", activitiProcessEngine.getRuntimeService().getVariable(processInstance.getId(), "newVariable"));
        }
        finally
        {
            cleanupProcessInstance(processInstance);
        }
    }
    
    @Test
    @SuppressWarnings("unchecked")
    public void testUpdateTaskVariablesExceptions() throws Exception
    {
        RequestContext requestContext = initApiClientWithTestUser();

        ProcessInstance processInstance = startAdhocProcess(requestContext.getRunAsUser(), requestContext.getNetworkId(), null);
        try
        {
            Task task = activitiProcessEngine.getTaskService().createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
            assertNotNull(task);
            
            // Update without name
            ObjectNode variableBody = AlfrescoDefaultObjectMapper.createObjectNode();
            variableBody.put("value", 1234);
            variableBody.put("scope", "global");
            
            TasksClient tasksClient = publicApiClient.tasksClient();
            try 
            {
                tasksClient.updateTaskVariable(task.getId(), "newVariable", variableBody);
                fail("Exception expected");
            }
            catch(PublicApiException expected)
            {
                assertEquals(HttpStatus.BAD_REQUEST.value(), expected.getHttpResponse().getStatusCode());
                assertErrorSummary("Variable name is required.", expected.getHttpResponse());
            }
            
            // Update without scope
            variableBody = AlfrescoDefaultObjectMapper.createObjectNode();
            variableBody.put("value", 1234);
            variableBody.put("name", "newVariable");
            
            try 
            {
                tasksClient.updateTaskVariable(task.getId(), "newVariable", variableBody);
                fail("Exception expected");
            }
            catch(PublicApiException expected)
            {
                assertEquals(HttpStatus.BAD_REQUEST.value(), expected.getHttpResponse().getStatusCode());
                assertErrorSummary("Variable scope is required and can only be 'local' or 'global'.", expected.getHttpResponse());
            }
            
            // Update in 'any' scope
            variableBody = AlfrescoDefaultObjectMapper.createObjectNode();
            variableBody.put("value", 1234);
            variableBody.put("name", "newVariable");
            variableBody.put("scope", "any");
            
            try 
            {
                tasksClient.updateTaskVariable(task.getId(), "newVariable", variableBody);
                fail("Exception expected");
            }
            catch(PublicApiException expected)
            {
                assertEquals(HttpStatus.BAD_REQUEST.value(), expected.getHttpResponse().getStatusCode());
                assertErrorSummary("Variable scope is required and can only be 'local' or 'global'.", expected.getHttpResponse());
            }
            
            // Update in illegal scope
            variableBody = AlfrescoDefaultObjectMapper.createObjectNode();
            variableBody.put("value", 1234);
            variableBody.put("name", "newVariable");
            variableBody.put("scope", "illegal");
            
            try 
            {
                tasksClient.updateTaskVariable(task.getId(), "newVariable", variableBody);
                fail("Exception expected");
            }
            catch(PublicApiException expected)
            {
                assertEquals(HttpStatus.BAD_REQUEST.value(), expected.getHttpResponse().getStatusCode());
                assertErrorSummary("Illegal value for variable scope: 'illegal'.", expected.getHttpResponse());
            }
            
            // Update using unsupported type
            variableBody = AlfrescoDefaultObjectMapper.createObjectNode();
            variableBody.put("value", 1234);
            variableBody.put("name", "newVariable");
            variableBody.put("scope", "local");
            variableBody.put("type", "d:unexisting");
            
            try 
            {
                tasksClient.updateTaskVariable(task.getId(), "newVariable", variableBody);
                fail("Exception expected");
            }
            catch(PublicApiException expected)
            {
                assertEquals(HttpStatus.BAD_REQUEST.value(), expected.getHttpResponse().getStatusCode());
                assertErrorSummary("Unsupported type of variable: 'd:unexisting'.", expected.getHttpResponse());
            }
            
            // Update using unsupported type (invalid QName)
            variableBody = AlfrescoDefaultObjectMapper.createObjectNode();
            variableBody.put("value", 1234);
            variableBody.put("name", "newVariable");
            variableBody.put("scope", "local");
            variableBody.put("type", " 12unexisting");
            
            try 
            {
                tasksClient.updateTaskVariable(task.getId(), "newVariable", variableBody);
                fail("Exception expected");
            }
            catch(PublicApiException expected)
            {
                assertEquals(HttpStatus.BAD_REQUEST.value(), expected.getHttpResponse().getStatusCode());
                assertErrorSummary("Unsupported type of variable: ' 12unexisting'.", expected.getHttpResponse());
            }
        }
        finally
        {
            cleanupProcessInstance(processInstance);
        }
    }
    
    @SuppressWarnings("unchecked")
    @Test
    public void testUpdateTaskVariableWithWrongType() throws Exception
    {
        final RequestContext requestContext = initApiClientWithTestUser();
        
        ProcessInfo processRest = startParallelReviewProcess(requestContext);
        
        try
        {
            List<Task> tasks = activitiProcessEngine.getTaskService().createTaskQuery().processInstanceId(processRest.getId()).list();
            assertNotNull(tasks);
            
            String taskId = tasks.get(0).getId();
            
            // Update an existing variable with wrong type
            ObjectNode variableJson = AlfrescoDefaultObjectMapper.createObjectNode();
            variableJson.put("name", "wf_requiredApprovePercent");
            variableJson.put("value", 55.99);
            variableJson.put("type", "d:double");
            variableJson.put("scope", "global");
            
            try 
            {
                publicApiClient.tasksClient().updateTaskVariable(taskId, "wf_requiredApprovePercent", variableJson);
                fail("Exception expected");
            }
            catch (PublicApiException e)
            {
                assertEquals(HttpStatus.BAD_REQUEST.value(), e.getHttpResponse().getStatusCode());
            }
            
            variableJson = AlfrescoDefaultObjectMapper.createObjectNode();
            variableJson.put("name", "wf_requiredApprovePercent");
            variableJson.put("value", 55.99);
            variableJson.put("type", "d:int");
            variableJson.put("scope", "global");
            
            JsonNode resultEntry = publicApiClient.tasksClient().updateTaskVariable(taskId, "wf_requiredApprovePercent", variableJson);
            assertNotNull(resultEntry);
            JsonNode result = resultEntry.get("entry");
            
            assertEquals("wf_requiredApprovePercent", result.get("name").textValue());
            assertEquals(55L, result.get("value").longValue());
            assertEquals("d:int", result.get("type").textValue());
            assertEquals(55, activitiProcessEngine.getRuntimeService().getVariable(processRest.getId(), "wf_requiredApprovePercent"));
            
            JsonNode taskVariables = publicApiClient.tasksClient().findTaskVariables(taskId);
            assertNotNull(taskVariables);
            JsonNode list = taskVariables.get("list");
            assertNotNull(list);
            
            // Add process variables to map for easy lookup
            Map<String, JsonNode> variablesByName = new HashMap<>();
            JsonNode entry = null;
            ArrayNode entries = (ArrayNode) list.get("entries");
            assertNotNull(entries);
            for(int i=0; i<entries.size(); i++) 
            {
                entry = entries.get(i);
                assertNotNull(entry);
                entry = entry.get("entry");
                assertNotNull(entry);
                variablesByName.put(entry.get("name").textValue(), entry);
            }
            
            JsonNode approvePercentObject = variablesByName.get("wf_requiredApprovePercent");
            assertNotNull(approvePercentObject);
            assertEquals(55L, approvePercentObject.get("value").longValue());
            assertEquals("d:int", approvePercentObject.get("type").textValue());
            
            // set a new variable
            variableJson = AlfrescoDefaultObjectMapper.createObjectNode();
            variableJson.put("name", "testVariable");
            variableJson.put("value", "text");
            variableJson.put("type", "d:text");
            variableJson.put("scope", "local");
            
            resultEntry = publicApiClient.tasksClient().updateTaskVariable(taskId, "testVariable", variableJson);
            assertNotNull(resultEntry);
            result = resultEntry.get("entry");
            
            assertEquals("testVariable", result.get("name").textValue());
            assertEquals("text", result.get("value").textValue());
            assertEquals("d:text", result.get("type").textValue());
            assertEquals("text", activitiProcessEngine.getTaskService().getVariable(taskId, "testVariable"));
            
            // change the variable value and type (should be working because no content model type)
            variableJson = AlfrescoDefaultObjectMapper.createObjectNode();
            variableJson.put("name", "testVariable");
            variableJson.put("value", 123);
            variableJson.put("type", "d:int");
            variableJson.put("scope", "local");
            
            resultEntry = publicApiClient.tasksClient().updateTaskVariable(taskId, "testVariable", variableJson);
            assertNotNull(resultEntry);
            result = resultEntry.get("entry");
            
            assertEquals("testVariable", result.get("name").textValue());
            assertEquals(123L, result.get("value").longValue());
            assertEquals("d:int", result.get("type").textValue());
            assertEquals(123, activitiProcessEngine.getTaskService().getVariable(taskId, "testVariable"));
            
            // change the variable value for a list of noderefs (bpm_assignees)
            final ObjectNode updateAssigneesJson = AlfrescoDefaultObjectMapper.createObjectNode();
            updateAssigneesJson.put("name", "bpm_assignees");
            updateAssigneesJson.put("type", "d:noderef");
            updateAssigneesJson.put("scope", "global");
            
            TenantUtil.runAsUserTenant(new TenantRunAsWork<Void>()
            {
                @Override
                public Void doWork() throws Exception
                {
                    ArrayNode assigneeArray = AlfrescoDefaultObjectMapper.createArrayNode();
                    assigneeArray.add(requestContext.getRunAsUser());
                    updateAssigneesJson.put("value", assigneeArray);
                    return null;
                }
            }, requestContext.getRunAsUser(), requestContext.getNetworkId());
            
            resultEntry = publicApiClient.tasksClient().updateTaskVariable(taskId, "bpm_assignees", updateAssigneesJson);
            assertNotNull(resultEntry);
            final JsonNode updateAssigneeResult = resultEntry.get("entry");
            
            assertEquals("bpm_assignees", updateAssigneeResult.get("name").textValue());
            TenantUtil.runAsUserTenant(new TenantRunAsWork<Void>()
            {
                @Override
                public Void doWork() throws Exception
                {
                    ArrayNode assigneeArray = (ArrayNode) updateAssigneeResult.get("value");
                    assertNotNull(assigneeArray);
                    assertEquals(1, assigneeArray.size());
                    return null;
                }
            }, requestContext.getRunAsUser(), requestContext.getNetworkId());
            
            assertEquals("d:noderef", updateAssigneeResult.get("type").textValue());
            
            // update the bpm_assignees with a single entry, should result in an error
            final ObjectNode updateAssigneeJson = AlfrescoDefaultObjectMapper.createObjectNode();
            updateAssigneeJson.put("name", "bpm_assignees");
            updateAssigneeJson.put("type", "d:noderef");
            updateAssigneeJson.put("scope", "global");
            
            TenantUtil.runAsUserTenant(new TenantRunAsWork<Void>()
            {
                @Override
                public Void doWork() throws Exception
                {
                    updateAssigneeJson.put("value", requestContext.getRunAsUser());
                    return null;
                }
            }, requestContext.getRunAsUser(), requestContext.getNetworkId());
            
            try 
            {
                publicApiClient.tasksClient().updateTaskVariable(taskId, "bpm_assignees", updateAssigneeJson);
                fail("Exception expected");
            }
            catch (PublicApiException e)
            {
                assertEquals(HttpStatus.BAD_REQUEST.value(), e.getHttpResponse().getStatusCode());
            }
            
        }
        finally
        {
            cleanupProcessInstance(processRest.getId());
        }
    }
    
    @Test
    @SuppressWarnings("unchecked")
    public void testUpdateTaskVariablesAuthentication() throws Exception
    {
        RequestContext requestContext = initApiClientWithTestUser();
        
        String initiator = getOtherPersonInNetwork(requestContext.getRunAsUser(), requestContext.getNetworkId()).getId();
        
        // Start process by one user and try to access the task variables as the task assignee instead of the process
        // initiator to see if the assignee is authorized to get the task
        ProcessInstance processInstance = startAdhocProcess(initiator, requestContext.getNetworkId(), null);
        try
        {
            ObjectNode variableBody = AlfrescoDefaultObjectMapper.createObjectNode();
            variableBody.put("name", "newVariable");
            variableBody.put("value", 1234);
            variableBody.put("scope", "global");
            
            Task task = activitiProcessEngine.getTaskService().createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
            assertNotNull(task);
            TasksClient tasksClient = publicApiClient.tasksClient();
            
            // Try updating task variables when NOT involved in the task
            try 
            {
                tasksClient.updateTaskVariable(task.getId(), "newVariable", variableBody);
                fail("Exception expected");
            } 
            catch(PublicApiException expected) 
            {
                assertEquals(HttpStatus.FORBIDDEN.value(), expected.getHttpResponse().getStatusCode());
                assertErrorSummary("Permission was denied", expected.getHttpResponse());
            }
            
            // Set assignee, task variables should be updatable now
            activitiProcessEngine.getTaskService().setAssignee(task.getId(), requestContext.getRunAsUser());
            JsonNode jsonObject = tasksClient.updateTaskVariable(task.getId(), "newVariable", variableBody);
            assertNotNull(jsonObject);
            
            // Updating task variables as admin should be possible
            String tenantAdmin = AuthenticationUtil.getAdminUserName() + "@" + requestContext.getNetworkId();
            publicApiClient.setRequestContext(new RequestContext(TenantUtil.DEFAULT_TENANT, tenantAdmin));
            jsonObject = tasksClient.updateTaskVariable(task.getId(), "newVariable", variableBody);
            assertNotNull(jsonObject);
            
            // Updating the task variables as a admin from another tenant shouldn't be possible
            TestNetwork anotherNetwork = getOtherNetwork(requestContext.getNetworkId());
            tenantAdmin = AuthenticationUtil.getAdminUserName() + "@" + anotherNetwork.getId();
            publicApiClient.setRequestContext(new RequestContext(TenantUtil.DEFAULT_TENANT, tenantAdmin));
            try 
            {
                jsonObject = tasksClient.updateTaskVariable(task.getId(), "newVariable", variableBody);
                fail("Exception expected");
            } 
            catch(PublicApiException expected) 
            {
                assertEquals(HttpStatus.FORBIDDEN.value(), expected.getHttpResponse().getStatusCode());
                assertErrorSummary("Permission was denied", expected.getHttpResponse());
            }
        }
        finally
        {
            cleanupProcessInstance(processInstance);
        }
    }
    
    @Test
    @SuppressWarnings("unchecked")
    public void testCreateTaskVariablesPresentInModel() throws Exception
    {
        RequestContext requestContext = initApiClientWithTestUser();

        ProcessInstance processInstance = startAdhocProcess(requestContext.getRunAsUser(), requestContext.getNetworkId(), null);
        try
        {
            Task task = activitiProcessEngine.getTaskService().createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
            assertNotNull(task);
            
            Map<String, Object> actualLocalVariables = activitiProcessEngine.getTaskService().getVariablesLocal(task.getId());
            Map<String, Object> actualGlobalVariables = activitiProcessEngine.getRuntimeService().getVariables(processInstance.getId());
            assertEquals(5, actualGlobalVariables.size());
            assertEquals(8, actualLocalVariables.size());
            
            // Update a global value that is present in the model with type given
            ArrayNode variablesArray = AlfrescoDefaultObjectMapper.createArrayNode();
            ObjectNode variableBody = AlfrescoDefaultObjectMapper.createObjectNode();
            variableBody.put("name", "bpm_percentComplete");
            variableBody.put("value", 20);
            variableBody.put("type", "d:int");
            variableBody.put("scope", "global");
            variablesArray.add(variableBody);
            variableBody = AlfrescoDefaultObjectMapper.createObjectNode();
            variableBody.put("name", "bpm_workflowPriority");
            variableBody.put("value", 50);
            variableBody.put("type", "d:int");
            variableBody.put("scope", "local");
            variablesArray.add(variableBody);
            
            TasksClient tasksClient = publicApiClient.tasksClient();
            JsonNode result = tasksClient.createTaskVariables(task.getId(), variablesArray);
            assertNotNull(result);
            JsonNode resultObject = result.get("list");
            ArrayNode resultList = (ArrayNode) resultObject.get("entries");
            assertEquals(2, resultList.size());
            JsonNode firstResultObject = (resultList.get(0)).get("entry");
            assertEquals("bpm_percentComplete", firstResultObject.get("name").textValue());
            assertEquals(20L, firstResultObject.get("value").longValue());
            assertEquals("d:int", firstResultObject.get("type").textValue());
            assertEquals("global", firstResultObject.get("scope").textValue());
            assertEquals(20, activitiProcessEngine.getRuntimeService().getVariable(processInstance.getId(), "bpm_percentComplete"));
            
            JsonNode secondResultObject = (resultList.get(1)).get("entry");
            assertEquals("bpm_workflowPriority", secondResultObject.get("name").textValue());
            assertEquals(50L, secondResultObject.get("value").longValue());
            assertEquals("d:int", secondResultObject.get("type").textValue());
            assertEquals("local", secondResultObject.get("scope").textValue());
            assertEquals(50, activitiProcessEngine.getTaskService().getVariable(task.getId(), "bpm_workflowPriority"));
        }
        finally
        {
            cleanupProcessInstance(processInstance);
        }
    }
    
    @Test
    public void testGetTaskVariablesRawVariableTypes() throws Exception
    {
        RequestContext requestContext = initApiClientWithTestUser();

        ProcessInstance processInstance = startAdhocProcess(requestContext.getRunAsUser(), requestContext.getNetworkId(), null);
        try
        {
            Task task = activitiProcessEngine.getTaskService().createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
            assertNotNull(task);
            
            Calendar dateCal = Calendar.getInstance();
            
            // Set all supported variables on the task to check the resulting types
            Map<String, Object> variablesToSet = new HashMap<String, Object>();
            variablesToSet.put("testVarString", "string");
            variablesToSet.put("testVarInteger", 1234);
            variablesToSet.put("testVarLong", 123456789L);
            variablesToSet.put("testVarDouble", 1234.5678D);
            variablesToSet.put("testVarFloat", 1234.0F);
            variablesToSet.put("testVarBoolean", Boolean.TRUE);
            variablesToSet.put("testVarDate", dateCal.getTime());
            variablesToSet.put("testVarQName", ContentModel.TYPE_AUTHORITY);
            variablesToSet.put("testVarNodeRef", new ActivitiScriptNode(new NodeRef("workspace:///testNode"), serviceRegistry));
            variablesToSet.put("testVarRawNodeRef", new NodeRef("workspace:///testNode"));
            
            activitiProcessEngine.getTaskService().setVariablesLocal(task.getId(), variablesToSet);
            Map<String, Object> actualLocalVariables = activitiProcessEngine.getTaskService().getVariablesLocal(task.getId());
            
            TasksClient tasksClient = publicApiClient.tasksClient();
            
            // Get all local variables
            JsonNode variables = tasksClient.findTaskVariables(task.getId(), Collections.singletonMap("where", "(scope = local)"));
            assertNotNull(variables);
            JsonNode list = variables.get("list");
            assertNotNull(list);
            ArrayNode entries = (ArrayNode) list.get("entries");
            assertNotNull(entries);
            
            // Check pagination object for size
            JsonNode pagination = list.get("pagination");
            assertNotNull(pagination);
            assertEquals(actualLocalVariables.size(), pagination.get("count").longValue());
            assertEquals(actualLocalVariables.size(), pagination.get("totalItems").longValue());
            assertEquals(0L, pagination.get("skipCount").longValue());
            assertFalse(pagination.get("hasMoreItems").booleanValue());
            
            assertEquals(actualLocalVariables.size(), entries.size());
            
            // Add JSON entries to map for easy access when asserting values
            Map<String, JsonNode> entriesByName = new HashMap<>();
            for (int i = 0; i < entries.size(); i++)
            {
                JsonNode var = entries.get(i);
                entriesByName.put(var.get("entry").get("name").textValue(), var.get("entry"));
            }
            
            // Check all values and types
            JsonNode var = entriesByName.get("testVarString");
            assertNotNull(var);
            assertEquals("d:text", var.get("type").textValue());
            assertEquals("string", var.get("value").textValue());
            
            var = entriesByName.get("testVarInteger");
            assertNotNull(var);
            assertEquals("d:int", var.get("type").textValue());
            assertEquals(1234L, var.get("value").longValue());
            
            var = entriesByName.get("testVarLong");
            assertNotNull(var);
            assertEquals("d:long", var.get("type").textValue());
            assertEquals(123456789L, var.get("value").longValue());
            
            var = entriesByName.get("testVarDouble");
            assertNotNull(var);
            assertEquals("d:double", var.get("type").textValue());
            assertEquals(1234.5678D, var.get("value").doubleValue(), 0);
            
            var = entriesByName.get("testVarFloat");
            assertNotNull(var);
            assertEquals("d:float", var.get("type").textValue());
            assertEquals(1234.0D, var.get("value").doubleValue(), 0);
            
            var = entriesByName.get("testVarBoolean");
            assertNotNull(var);
            assertEquals("d:boolean", var.get("type").textValue());
            assertEquals(Boolean.TRUE, var.get("value").booleanValue());
            
            var = entriesByName.get("testVarDate");
            assertNotNull(var);
            assertEquals("d:datetime", var.get("type").textValue());
            assertEquals(dateCal.getTime(), parseDate(var, "value"));
            
            var = entriesByName.get("testVarQName");
            assertNotNull(var);
            assertEquals("d:qname", var.get("type").textValue());
            assertEquals("cm:authority", var.get("value").textValue());
            
            var = entriesByName.get("testVarRawNodeRef");
            assertNotNull(var);
            assertEquals("d:noderef", var.get("type").textValue());
            assertEquals("workspace:///testNode", var.get("value").textValue());
            
            var = entriesByName.get("testVarNodeRef");
            assertNotNull(var);
            assertEquals("d:noderef", var.get("type").textValue());
            assertEquals("workspace:///testNode", var.get("value").textValue());
               
        }
        finally
        {
            cleanupProcessInstance(processInstance);
        }
    }
    
    @Test
    public void testGetTaskVariablesScoped() throws Exception
    {
        RequestContext requestContext = initApiClientWithTestUser();

        ProcessInstance processInstance = startAdhocProcess(requestContext.getRunAsUser(), requestContext.getNetworkId(), null);
        try
        {
            Task task = activitiProcessEngine.getTaskService().createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
            assertNotNull(task);
            
            Map<String, Object> actualLocalVariables = activitiProcessEngine.getTaskService().getVariablesLocal(task.getId());
            Map<String, Object> actualGlobalVariables = activitiProcessEngine.getRuntimeService().getVariables(processInstance.getId());
            assertEquals(5, actualGlobalVariables.size());
            assertEquals(8, actualLocalVariables.size());
            
            TasksClient tasksClient = publicApiClient.tasksClient();
            
            JsonNode variables = tasksClient.findTaskVariables(task.getId(), Collections.singletonMap("where", "(scope = local)"));
            assertNotNull(variables);
            JsonNode list = variables.get("list");
            assertNotNull(list);
            ArrayNode entries = (ArrayNode) list.get("entries");
            assertNotNull(entries);
            
            // Check pagination object for size
            JsonNode pagination = list.get("pagination");
            assertNotNull(pagination);
            assertEquals(8L, pagination.get("count").longValue());
            assertEquals(8L, pagination.get("totalItems").longValue());
            assertEquals(0L, pagination.get("skipCount").longValue());
            assertFalse(pagination.get("hasMoreItems").booleanValue());
            
            assertEquals(actualLocalVariables.size(), entries.size());
            
            Set<String> expectedLocalVars = new HashSet<String>();
            expectedLocalVars.addAll(actualLocalVariables.keySet());
            
            for(int i=0; i < entries.size(); i++) 
            {
                JsonNode entry = (entries.get(i)).get("entry");
                assertNotNull(entry);
                
                // Check if full entry is present with correct scope
                assertEquals("local", entry.get("scope").textValue());
                assertNotNull(entry.get("name"));
                assertNotNull(entry.get("type"));
                if(!entry.get("name").textValue().equals("bpm_hiddenTransitions")) {
                    assertNotNull(entry.get("value"));
                }
                expectedLocalVars.remove(entry.get("name").textValue());
            }
            
            assertEquals(0, expectedLocalVars.size());
            
            // Now check the global scope
            variables = tasksClient.findTaskVariables(task.getId(), Collections.singletonMap("where", "(scope = global)"));
            assertNotNull(variables);
            list = variables.get("list");
            assertNotNull(list);
            entries = (ArrayNode) list.get("entries");
            assertNotNull(entries);
            
            // Check pagination object for size
            pagination = list.get("pagination");
            assertNotNull(pagination);
            assertEquals(4L, pagination.get("count").longValue());
            assertEquals(4L, pagination.get("totalItems").longValue());
            assertEquals(0L, pagination.get("skipCount").longValue());
            assertFalse(pagination.get("hasMoreItems").booleanValue());
            
            // Should contain one variable less than the actual variables in the engine, tenant-domain var is filtered out
            assertEquals(actualGlobalVariables.size() - 1, entries.size());
            
            Set<String> expectedGlobalVars = new HashSet<String>();
            expectedGlobalVars.addAll(actualGlobalVariables.keySet());
            expectedGlobalVars.remove(ActivitiConstants.VAR_TENANT_DOMAIN);
            
            for(int i=0; i < entries.size(); i++) 
            {
                JsonNode entry = (entries.get(i)).get("entry");
                assertNotNull(entry);
                
                // Check if full entry is present with correct scope
                assertEquals("global", entry.get("scope").textValue());
                assertNotNull(entry.get("name"));
                assertNotNull(entry.get("type"));
                assertNotNull(entry.get("value"));
                expectedGlobalVars.remove(entry.get("name").textValue());
            }
            
            // Check if all variables are present
            assertEquals(0, expectedGlobalVars.size());
            
        }
        finally
        {
            cleanupProcessInstance(processInstance);
        }
    }
    
    @Test
    public void testGetTaskVariablesReview() throws Exception
    {
        RequestContext requestContext = initApiClientWithTestUser();

        ProcessInfo processInstance = startParallelReviewProcess(requestContext);
        try
        {
            Task task = activitiProcessEngine.getTaskService().createTaskQuery()
                    .processInstanceId(processInstance.getId())
                    .taskAssignee(requestContext.getRunAsUser())
                    .singleResult();
            
            assertNotNull(task);
            
            TasksClient tasksClient = publicApiClient.tasksClient();
            
            JsonNode variables = tasksClient.findTaskVariables(task.getId());
            assertNotNull(variables);
            JsonNode list = variables.get("list");
            assertNotNull(list);
            ArrayNode entries = (ArrayNode) list.get("entries");
            assertNotNull(entries);
            
            // Check pagination object for size
            JsonNode pagination = list.get("pagination");
            assertNotNull(pagination);
            assertEquals(42L, pagination.get("count").longValue());
            assertEquals(42L, pagination.get("totalItems").longValue());
            assertEquals(0L, pagination.get("skipCount").longValue());
            assertFalse(pagination.get("hasMoreItems").booleanValue());
        }
        finally
        {
            cleanupProcessInstance(processInstance.getId());
        }
    }
    
    @Test
    public void testDeleteTaskVariable() throws Exception
    {
        RequestContext requestContext = initApiClientWithTestUser();
        
        ProcessInstance processInstance = startAdhocProcess(requestContext.getRunAsUser(), requestContext.getNetworkId(), null);
        activitiProcessEngine.getRuntimeService().setVariable(processInstance.getId(), "overlappingVariable", "Value set in process");
        try
        {
            Task task = activitiProcessEngine.getTaskService().createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
            assertNotNull(task);
            activitiProcessEngine.getTaskService().setVariableLocal(task.getId(), "myVariable", "This is a variable");
            activitiProcessEngine.getTaskService().setVariableLocal(task.getId(), "overlappingVariable", "Value set in task");
            
            // Delete a task-variable
            TasksClient tasksClient = publicApiClient.tasksClient();
            tasksClient.deleteTaskVariable(task.getId(), "myVariable");
            assertFalse(activitiProcessEngine.getTaskService().hasVariableLocal(task.getId(), "myVariable"));
            
            // Delete a task-variable that has the same name as a global process-variable - which should remain untouched after delete
            tasksClient.deleteTaskVariable(task.getId(), "overlappingVariable");
            assertFalse(activitiProcessEngine.getTaskService().hasVariableLocal(task.getId(), "overlappingVariable"));
            assertTrue(activitiProcessEngine.getRuntimeService().hasVariable(processInstance.getId(), "overlappingVariable"));
            assertEquals("Value set in process", activitiProcessEngine.getRuntimeService().getVariable(processInstance.getId(), "overlappingVariable"));
        }
        finally
        {
            cleanupProcessInstance(processInstance);
        }
    }
    
    @Test
    public void testDeleteTaskVariableExceptions() throws Exception
    {
        RequestContext requestContext = initApiClientWithTestUser();
        
        ProcessInstance processInstance = startAdhocProcess(requestContext.getRunAsUser(), requestContext.getNetworkId(), null);
        activitiProcessEngine.getRuntimeService().setVariable(processInstance.getId(), "overlappingVariable", "Value set in process");
        try
        {
            Task task = activitiProcessEngine.getTaskService().createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
            assertNotNull(task);
           
            TasksClient tasksClient = publicApiClient.tasksClient();
            
            // Delete a variable on an unexisting task
            try 
            {
                tasksClient.deleteTaskVariable("unexisting", "myVar");
                fail("Exception expected");
            }
            catch(PublicApiException expected)
            {
                assertEquals(HttpStatus.NOT_FOUND.value(), expected.getHttpResponse().getStatusCode());
                assertErrorSummary("The entity with id: unexisting was not found", expected.getHttpResponse());
            }
            
            // Delete an unexisting variable on an existing task
            try 
            {
                tasksClient.deleteTaskVariable(task.getId(), "unexistingVarName");
                fail("Exception expected");
            }
            catch(PublicApiException expected)
            {
                assertEquals(HttpStatus.NOT_FOUND.value(), expected.getHttpResponse().getStatusCode());
                assertErrorSummary("The entity with id: unexistingVarName was not found", expected.getHttpResponse());
            }
            
        }
        finally
        {
            cleanupProcessInstance(processInstance);
        }
    }
    
    @Test
    public void testDeleteTaskVariableAuthentication() throws Exception
    {
        RequestContext requestContext = initApiClientWithTestUser();
        
        String initiator = getOtherPersonInNetwork(requestContext.getRunAsUser(), requestContext.getNetworkId()).getId();
        
        // Start process by one user and try to access the task variables as the task assignee instead of the process
        // initiator to see if the assignee is authorized to get the task
        ProcessInstance processInstance = startAdhocProcess(initiator, requestContext.getNetworkId(), null);
        try
        {
            Task task = activitiProcessEngine.getTaskService().createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
            assertNotNull(task);
            TasksClient tasksClient = publicApiClient.tasksClient();
            activitiProcessEngine.getTaskService().setVariableLocal(task.getId(), "existingVariable", "Value");
            
            // Try updating task variables when NOT involved in the task
            try {
                tasksClient.deleteTaskVariable(task.getId(), "existingVariable");
                fail("Exception expected");
            } catch(PublicApiException expected) {
                assertEquals(HttpStatus.FORBIDDEN.value(), expected.getHttpResponse().getStatusCode());
                assertErrorSummary("Permission was denied", expected.getHttpResponse());
            }
            assertTrue(activitiProcessEngine.getTaskService().hasVariableLocal(task.getId(), "existingVariable"));
            
            // Set assignee, task variables should be updatable now
            activitiProcessEngine.getTaskService().setAssignee(task.getId(), requestContext.getRunAsUser());
            tasksClient.deleteTaskVariable(task.getId(), "existingVariable");
            assertFalse(activitiProcessEngine.getTaskService().hasVariableLocal(task.getId(), "existingVariable"));
            activitiProcessEngine.getTaskService().setVariableLocal(task.getId(), "existingVariable", "Value");
            
            // Updating task variables as admin should be possible
            String tenantAdmin = AuthenticationUtil.getAdminUserName() + "@" + requestContext.getNetworkId();
            publicApiClient.setRequestContext(new RequestContext(TenantUtil.DEFAULT_TENANT, tenantAdmin));
            tasksClient.deleteTaskVariable(task.getId(), "existingVariable");
            assertFalse(activitiProcessEngine.getTaskService().hasVariableLocal(task.getId(), "existingVariable"));
            activitiProcessEngine.getTaskService().setVariableLocal(task.getId(), "existingVariable", "Value");
            
            // Updating the task variables as a admin from another tenant shouldn't be possible
            TestNetwork anotherNetwork = getOtherNetwork(requestContext.getNetworkId());
            tenantAdmin = AuthenticationUtil.getAdminUserName() + "@" + anotherNetwork.getId();
            publicApiClient.setRequestContext(new RequestContext(TenantUtil.DEFAULT_TENANT, tenantAdmin));
            try {
                tasksClient.deleteTaskVariable(task.getId(), "existingVariable");
                fail("Exception expected");
            } catch(PublicApiException expected) {
                assertEquals(HttpStatus.FORBIDDEN.value(), expected.getHttpResponse().getStatusCode());
                assertErrorSummary("Permission was denied", expected.getHttpResponse());
            }
        }
        finally
        {
            cleanupProcessInstance(processInstance);
        }
    }
    
    @Test
    public void testGetTaskModel() throws Exception
    {
        RequestContext requestContext = initApiClientWithTestUser();
        
        String otherPerson = getOtherPersonInNetwork(requestContext.getRunAsUser(), requestContext.getNetworkId()).getId();
        RequestContext otherContext = new RequestContext(requestContext.getNetworkId(), otherPerson);
        
        String tenantAdmin = AuthenticationUtil.getAdminUserName() + "@" + requestContext.getNetworkId();
        RequestContext adminContext = new RequestContext(requestContext.getNetworkId(), tenantAdmin);
        
        ProcessInstance processInstance = startAdhocProcess(requestContext.getRunAsUser(), requestContext.getNetworkId(), null);
        try
        {
            Task task = activitiProcessEngine.getTaskService().createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
            assertNotNull(task);
            
            JsonNode model = publicApiClient.tasksClient().findTaskFormModel(task.getId());
            assertNotNull(model);
            
            ArrayNode entries = (ArrayNode) model.get("entries");
            assertNotNull(entries);
            
            // Add all entries to a map, to make lookup easier
            Map<String, JsonNode> modelFieldsByName = createEntryMap(entries);
            testModelEntries(modelFieldsByName);
            
            // get task form model with admin
            publicApiClient.setRequestContext(adminContext);
            model = publicApiClient.tasksClient().findTaskFormModel(task.getId());
            assertNotNull(model);
            
            entries = (ArrayNode) model.get("entries");
            assertNotNull(entries);
            
            modelFieldsByName = createEntryMap(entries);
            testModelEntries(modelFieldsByName);
            
            // get task form model with non involved user
            publicApiClient.setRequestContext(otherContext);
            try
            {
                publicApiClient.tasksClient().findTaskFormModel(task.getId());
            }
            catch (PublicApiException e)
            {
                assertEquals(403, e.getHttpResponse().getStatusCode());
            }
            
            // get task form model with invalid task id
            publicApiClient.setRequestContext(requestContext);
            try
            {
                publicApiClient.tasksClient().findTaskFormModel("fakeid");
            }
            catch (PublicApiException e)
            {
                assertEquals(404, e.getHttpResponse().getStatusCode());
            }
        }
        finally
        {
            cleanupProcessInstance(processInstance);
        }
    }
    
    protected Map<String, JsonNode> createEntryMap(ArrayNode entries)
    {
        Map<String, JsonNode> modelFieldsByName = new HashMap<>();
        JsonNode entry = null;
        for(int i=0; i<entries.size(); i++) 
        {
            entry = entries.get(i);
            assertNotNull(entry);
            entry = entry.get("entry");
            assertNotNull(entry);
            modelFieldsByName.put(entry.get("name").textValue(), entry);
        }
        return modelFieldsByName;
    }
    
    protected void testModelEntries(Map<String, JsonNode> modelFieldsByName)
    {
        // Check well-known properties and their types
        
        // Validate bpm:completionDate
        JsonNode modelEntry = modelFieldsByName.get("bpm_completionDate");
        assertNotNull(modelEntry);
        assertEquals("Completion Date", modelEntry.get("title").textValue());
        assertEquals("{http://www.alfresco.org/model/bpm/1.0}completionDate", modelEntry.get("qualifiedName").textValue());
        assertEquals("d:date", modelEntry.get("dataType").textValue());
        assertFalse(modelEntry.get("required").booleanValue());
        
        // Validate cm:owner
        modelEntry = modelFieldsByName.get("cm_owner");
        assertNotNull(modelEntry);
        assertEquals("Owner", modelEntry.get("title").textValue());
        assertEquals("{http://www.alfresco.org/model/content/1.0}owner", modelEntry.get("qualifiedName").textValue());
        assertEquals("d:text", modelEntry.get("dataType").textValue());
        assertFalse(modelEntry.get("required").booleanValue());
        
        // Validate bpm:package
        modelEntry = modelFieldsByName.get("bpm_package");
        assertNotNull(modelEntry);
        assertEquals("Content Package", modelEntry.get("title").textValue());
        assertEquals("{http://www.alfresco.org/model/bpm/1.0}package", modelEntry.get("qualifiedName").textValue());
        assertEquals("bpm:workflowPackage", modelEntry.get("dataType").textValue());
        assertFalse(modelEntry.get("required").booleanValue());
    }
    
    @Test
    public void testGetTaskItems() throws Exception
    {
        final RequestContext requestContext = initApiClientWithTestUser();
        
        String otherPerson = getOtherPersonInNetwork(requestContext.getRunAsUser(), requestContext.getNetworkId()).getId();
        RequestContext otherContext = new RequestContext(requestContext.getNetworkId(), otherPerson);
        
        String tenantAdmin = AuthenticationUtil.getAdminUserName() + "@" + requestContext.getNetworkId();
        RequestContext adminContext = new RequestContext(requestContext.getNetworkId(), tenantAdmin);
        
        // Create test-document and add to package
        NodeRef[] docNodeRefs = createTestDocuments(requestContext);
        ProcessInfo processInfo = startAdhocProcess(requestContext, docNodeRefs);
        
        final Task task = activitiProcessEngine.getTaskService().createTaskQuery()
            .processInstanceId(processInfo.getId()).singleResult();
        
        assertNotNull(task);
        activitiProcessEngine.getTaskService().setAssignee(task.getId(), null);
        
        try
        {
            TasksClient tasksClient = publicApiClient.tasksClient();
            JsonNode itemsJSON = tasksClient.findTaskItems(task.getId());
            assertNotNull(itemsJSON);
            ArrayNode entriesJSON = (ArrayNode) itemsJSON.get("entries");
            assertNotNull(entriesJSON);
            assertTrue(entriesJSON.size() == 2);
            boolean doc1Found = false;
            boolean doc2Found = false;
            for (JsonNode entryObject : entriesJSON)
            {
                JsonNode entryJSON = entryObject.get("entry");
                if (entryJSON.get("name").textValue().equals("Test Doc1")) {
                    doc1Found = true;
                    assertEquals(docNodeRefs[0].getId(), entryJSON.get("id").textValue());
                    assertEquals("Test Doc1", entryJSON.get("name").textValue());
                    assertEquals("Test Doc1 Title", entryJSON.get("title").textValue());
                    assertEquals("Test Doc1 Description", entryJSON.get("description").textValue());
                    assertNotNull(entryJSON.get("createdAt"));
                    assertEquals(requestContext.getRunAsUser(), entryJSON.get("createdBy").textValue());
                    assertNotNull(entryJSON.get("modifiedAt"));
                    assertEquals(requestContext.getRunAsUser(), entryJSON.get("modifiedBy").textValue());
                    assertNotNull(entryJSON.get("size"));
                    assertNotNull(entryJSON.get("mimeType"));
                } else {
                    doc2Found = true;
                    assertEquals(docNodeRefs[1].getId(), entryJSON.get("id").textValue());
                    assertEquals("Test Doc2", entryJSON.get("name").textValue());
                    assertEquals("Test Doc2 Title", entryJSON.get("title").textValue());
                    assertEquals("Test Doc2 Description", entryJSON.get("description").textValue());
                    assertNotNull(entryJSON.get("createdAt"));
                    assertEquals(requestContext.getRunAsUser(), entryJSON.get("createdBy").textValue());
                    assertNotNull(entryJSON.get("modifiedAt"));
                    assertEquals(requestContext.getRunAsUser(), entryJSON.get("modifiedBy").textValue());
                    assertNotNull(entryJSON.get("size"));
                    assertNotNull(entryJSON.get("mimeType"));
                }
            }
            assertTrue(doc1Found);
            assertTrue(doc2Found);
            
            // get with admin
            publicApiClient.setRequestContext(adminContext);
            itemsJSON = tasksClient.findTaskItems(task.getId());
            assertNotNull(itemsJSON);
            entriesJSON = (ArrayNode) itemsJSON.get("entries");
            assertNotNull(entriesJSON);
            assertTrue(entriesJSON.size() == 2);
            
            // get with non involved user
            publicApiClient.setRequestContext(otherContext);
            try
            {
                tasksClient.findTaskItems(task.getId());
                fail("Expected exception");
            }
            catch (PublicApiException e)
            {
                assertEquals(403, e.getHttpResponse().getStatusCode());
            }
            
            // get with candidate user
            activitiProcessEngine.getTaskService().addCandidateUser(task.getId(), otherContext.getRunAsUser());
            publicApiClient.setRequestContext(otherContext);
            itemsJSON = tasksClient.findTaskItems(task.getId());
            assertNotNull(itemsJSON);
            entriesJSON = (ArrayNode) itemsJSON.get("entries");
            assertNotNull(entriesJSON);
            assertTrue(entriesJSON.size() == 2);
            
            // get with user from candidate group with no assignee
            List<MemberOfSite> memberships = getTestFixture().getNetwork(otherContext.getNetworkId()).getSiteMemberships(otherContext.getRunAsUser());
            assertTrue(memberships.size() > 0);
            MemberOfSite memberOfSite = memberships.get(0);
            String group = "GROUP_site_" + memberOfSite.getSiteId() + "_" + memberOfSite.getRole().name();
            
            activitiProcessEngine.getTaskService().deleteCandidateUser(task.getId(), otherContext.getRunAsUser());
            activitiProcessEngine.getTaskService().addCandidateGroup(task.getId(), group);
            publicApiClient.setRequestContext(otherContext);
            itemsJSON = tasksClient.findTaskItems(task.getId());
            assertNotNull(itemsJSON);
            entriesJSON = (ArrayNode) itemsJSON.get("entries");
            assertNotNull(entriesJSON);
            assertTrue(entriesJSON.size() == 2);
            
            // get with user from candidate group with assignee
            activitiProcessEngine.getTaskService().setAssignee(task.getId(), requestContext.getRunAsUser());
            try
            {
                tasksClient.findTaskItems(task.getId());
                fail("Expected exception");
            }
            catch (PublicApiException e)
            {
                assertEquals(403, e.getHttpResponse().getStatusCode());
            }
            
            // invalid task id
            publicApiClient.setRequestContext(requestContext);
            try
            {
                tasksClient.findTaskItems("fakeid");
                fail("Expected exception");
            }
            catch (PublicApiException e)
            {
                assertEquals(404, e.getHttpResponse().getStatusCode());
            }
            
            // get items from completed task with initiator
            TenantUtil.runAsUserTenant(new TenantRunAsWork<Void>()
            {
                @Override
                public Void doWork() throws Exception
                {
                    activitiProcessEngine.getTaskService().complete(task.getId());
                    return null;
                }
            }, requestContext.getRunAsUser(), requestContext.getNetworkId());
            
            publicApiClient.setRequestContext(requestContext);
            itemsJSON = tasksClient.findTaskItems(task.getId());
            assertNotNull(itemsJSON);
            entriesJSON = (ArrayNode) itemsJSON.get("entries");
            assertNotNull(entriesJSON);
            assertTrue(entriesJSON.size() == 2);
            
            // get items from completed task with user from candidate group
            publicApiClient.setRequestContext(otherContext);
            try
            {
                tasksClient.findTaskItems(task.getId());
                fail("Expected exception");
            }
            catch (PublicApiException e)
            {
                assertEquals(403, e.getHttpResponse().getStatusCode());
            }
        }
        finally
        {
            cleanupProcessInstance(processInfo.getId());
        }
    }
    
    @Test
    @SuppressWarnings("unchecked")
    public void testAddTaskItem() throws Exception
    {
        final RequestContext requestContext = initApiClientWithTestUser();
        
        String otherPerson = getOtherPersonInNetwork(requestContext.getRunAsUser(), requestContext.getNetworkId()).getId();
        RequestContext otherContext = new RequestContext(requestContext.getNetworkId(), otherPerson);
        
        String tenantAdmin = AuthenticationUtil.getAdminUserName() + "@" + requestContext.getNetworkId();
        RequestContext adminContext = new RequestContext(requestContext.getNetworkId(), tenantAdmin);
        
        // Create test-document and add to package
        NodeRef[] docNodeRefs = createTestDocuments(requestContext);
        ProcessInfo processInfo = startAdhocProcess(requestContext, null);
        
        final Task task = activitiProcessEngine.getTaskService()
                .createTaskQuery()
                .processInstanceId(processInfo.getId())
                .singleResult();
        
        assertNotNull(task);
        activitiProcessEngine.getTaskService().setAssignee(task.getId(), null);
        
        try
        {
            TasksClient tasksClient = publicApiClient.tasksClient();
            ObjectNode createItemObject = AlfrescoDefaultObjectMapper.createObjectNode();
            createItemObject.put("id", docNodeRefs[0].getId());
            JsonNode result = tasksClient.addTaskItem(task.getId(), AlfrescoDefaultObjectMapper.writeValueAsString(createItemObject));
            
            assertNotNull(result);
            assertEquals(docNodeRefs[0].getId(), result.get("id").textValue());
            assertEquals("Test Doc1", result.get("name").textValue());
            assertEquals("Test Doc1 Title", result.get("title").textValue());
            assertEquals("Test Doc1 Description", result.get("description").textValue());
            assertNotNull(result.get("createdAt"));
            assertEquals(requestContext.getRunAsUser(), result.get("createdBy").textValue());
            assertNotNull(result.get("modifiedAt"));
            assertEquals(requestContext.getRunAsUser(), result.get("modifiedBy").textValue());
            assertNotNull(result.get("size"));
            assertNotNull(result.get("mimeType"));
            
            JsonNode itemJSON = tasksClient.findTaskItem(task.getId(), docNodeRefs[0].getId());
            assertEquals(docNodeRefs[0].getId(), itemJSON.get("id").textValue());
            
            tasksClient.deleteTaskItem(task.getId(), docNodeRefs[0].getId());
            try
            {
                tasksClient.findTaskItem(task.getId(), docNodeRefs[0].getId());
                fail("Expected exception");
            }
            catch (PublicApiException e)
            {
                assertEquals(404, e.getHttpResponse().getStatusCode());
            }
            
            // add item as admin
            publicApiClient.setRequestContext(adminContext);
            result = tasksClient.addTaskItem(task.getId(), AlfrescoDefaultObjectMapper.writeValueAsString(createItemObject));
            assertNotNull(result);
            assertEquals(docNodeRefs[0].getId(), result.get("id").textValue());
            assertEquals("Test Doc1", result.get("name").textValue());
            
            itemJSON = tasksClient.findTaskItem(task.getId(), docNodeRefs[0].getId());
            assertEquals(docNodeRefs[0].getId(), itemJSON.get("id").textValue());
            
            tasksClient.deleteTaskItem(task.getId(), docNodeRefs[0].getId());
            try
            {
                tasksClient.findTaskItem(task.getId(), docNodeRefs[0].getId());
                fail("Expected exception");
            }
            catch (PublicApiException e)
            {
                assertEquals(404, e.getHttpResponse().getStatusCode());
            }
            
            // add item with candidate user
            activitiProcessEngine.getTaskService().addCandidateUser(task.getId(), otherPerson);
            publicApiClient.setRequestContext(otherContext);
            result = tasksClient.addTaskItem(task.getId(), AlfrescoDefaultObjectMapper.writeValueAsString(createItemObject));
            assertNotNull(result);
            assertEquals(docNodeRefs[0].getId(), result.get("id").textValue());
            assertEquals("Test Doc1", result.get("name").textValue());
            
            itemJSON = tasksClient.findTaskItem(task.getId(), docNodeRefs[0].getId());
            assertEquals(docNodeRefs[0].getId(), itemJSON.get("id").textValue());
            
            tasksClient.deleteTaskItem(task.getId(), docNodeRefs[0].getId());
            try
            {
                tasksClient.findTaskItem(task.getId(), docNodeRefs[0].getId());
                fail("Expected exception");
            }
            catch (PublicApiException e)
            {
                assertEquals(404, e.getHttpResponse().getStatusCode());
            }
            
            // add item with not involved user
            activitiProcessEngine.getTaskService().deleteCandidateUser(task.getId(), otherPerson);
            publicApiClient.setRequestContext(otherContext);
            try
            {
                tasksClient.addTaskItem(task.getId(), AlfrescoDefaultObjectMapper.writeValueAsString(createItemObject));
                fail("Expected exception");
            }
            catch (PublicApiException e)
            {
                assertEquals(403, e.getHttpResponse().getStatusCode());
            }
            
            // add item with user from candidate group with no assignee
            List<MemberOfSite> memberships = getTestFixture().getNetwork(otherContext.getNetworkId()).getSiteMemberships(otherContext.getRunAsUser());
            assertTrue(memberships.size() > 0);
            MemberOfSite memberOfSite = memberships.get(0);
            String group = "GROUP_site_" + memberOfSite.getSiteId() + "_" + memberOfSite.getRole().name();
            
            activitiProcessEngine.getTaskService().deleteCandidateUser(task.getId(), otherContext.getRunAsUser());
            activitiProcessEngine.getTaskService().addCandidateGroup(task.getId(), group);
            publicApiClient.setRequestContext(otherContext);
            result = tasksClient.addTaskItem(task.getId(), AlfrescoDefaultObjectMapper.writeValueAsString(createItemObject));
            assertNotNull(result);
            assertEquals(docNodeRefs[0].getId(), result.get("id").textValue());
            assertEquals("Test Doc1", result.get("name").textValue());
            
            itemJSON = tasksClient.findTaskItem(task.getId(), docNodeRefs[0].getId());
            assertEquals(docNodeRefs[0].getId(), itemJSON.get("id").textValue());
            
            tasksClient.deleteTaskItem(task.getId(), docNodeRefs[0].getId());
            try
            {
                tasksClient.findTaskItem(task.getId(), docNodeRefs[0].getId());
                fail("Expected exception");
            }
            catch (PublicApiException e)
            {
                assertEquals(404, e.getHttpResponse().getStatusCode());
            }
            
            // add item with user from candidate group with assignee
            activitiProcessEngine.getTaskService().setAssignee(task.getId(), requestContext.getRunAsUser());
            publicApiClient.setRequestContext(otherContext);
            try
            {
                tasksClient.addTaskItem(task.getId(), AlfrescoDefaultObjectMapper.writeValueAsString(createItemObject));
                fail("Expected exception");
            }
            catch (PublicApiException e)
            {
                assertEquals(403, e.getHttpResponse().getStatusCode());
            }
            
            // invalid task id
            publicApiClient.setRequestContext(requestContext);
            try
            {
                tasksClient.addTaskItem("fakeid", AlfrescoDefaultObjectMapper.writeValueAsString(createItemObject));
                fail("Expected exception");
            }
            catch (PublicApiException e)
            {
                assertEquals(404, e.getHttpResponse().getStatusCode());
            }
            
            // invalid item id
            createItemObject = AlfrescoDefaultObjectMapper.createObjectNode();
            createItemObject.put("id", "fakeid");
            try
            {
                tasksClient.addTaskItem(task.getId(), AlfrescoDefaultObjectMapper.writeValueAsString(createItemObject));
                fail("Expected exception");
            }
            catch (PublicApiException e)
            {
                assertEquals(404, e.getHttpResponse().getStatusCode());
            }
            
            // add item to completed task
            TenantUtil.runAsUserTenant(new TenantRunAsWork<Void>()
            {
                @Override
                public Void doWork() throws Exception
                {
                    activitiProcessEngine.getTaskService().complete(task.getId());
                    return null;
                }
            }, requestContext.getRunAsUser(), requestContext.getNetworkId());
            
            createItemObject = AlfrescoDefaultObjectMapper.createObjectNode();
            createItemObject.put("id", docNodeRefs[0].getId());
            try
            {
                tasksClient.addTaskItem(task.getId(), AlfrescoDefaultObjectMapper.writeValueAsString(createItemObject));
                fail("Expected exception");
            }
            catch (PublicApiException e)
            {
                assertEquals(404, e.getHttpResponse().getStatusCode());
            }
        }
        finally
        {
            cleanupProcessInstance(processInfo.getId());
        }
    }
    
    @Test
    @SuppressWarnings("unchecked")
    public void testDeleteTaskItem() throws Exception
    {
        final RequestContext requestContext = initApiClientWithTestUser();
        
        String otherPerson = getOtherPersonInNetwork(requestContext.getRunAsUser(), requestContext.getNetworkId()).getId();
        RequestContext otherContext = new RequestContext(requestContext.getNetworkId(), otherPerson);
        
        String tenantAdmin = AuthenticationUtil.getAdminUserName() + "@" + requestContext.getNetworkId();
        RequestContext adminContext = new RequestContext(requestContext.getNetworkId(), tenantAdmin);
        
        // Create test-document and add to package
        NodeRef[] docNodeRefs = createTestDocuments(requestContext);
        ProcessInfo processInfo = startAdhocProcess(requestContext, docNodeRefs);
        
        final Task task = activitiProcessEngine.getTaskService()
                .createTaskQuery()
                .processInstanceId(processInfo.getId())
                .singleResult();
        
        assertNotNull(task);
        activitiProcessEngine.getTaskService().setAssignee(task.getId(), null);
        
        try
        {
            TasksClient tasksClient = publicApiClient.tasksClient();
            tasksClient.deleteTaskItem(task.getId(), docNodeRefs[0].getId());
            
            try
            {
                tasksClient.findTaskItem(task.getId(), docNodeRefs[0].getId());
                fail("Expected exception");
            }
            catch (PublicApiException e)
            {
                assertEquals(404, e.getHttpResponse().getStatusCode());
            }
            
            // delete item as admin
            ObjectNode createItemObject = AlfrescoDefaultObjectMapper.createObjectNode();
            createItemObject.put("id", docNodeRefs[0].getId());
            JsonNode result = tasksClient.addTaskItem(task.getId(), AlfrescoDefaultObjectMapper.writeValueAsString(createItemObject));
            
            assertNotNull(result);
            assertEquals(docNodeRefs[0].getId(), result.get("id").textValue());
            
            JsonNode itemJSON = tasksClient.findTaskItem(task.getId(), docNodeRefs[0].getId());
            assertEquals(docNodeRefs[0].getId(), itemJSON.get("id").textValue());
            
            publicApiClient.setRequestContext(adminContext);
            tasksClient.deleteTaskItem(task.getId(), docNodeRefs[0].getId());
            try
            {
                tasksClient.findTaskItem(task.getId(), docNodeRefs[0].getId());
                fail("Expected exception");
            }
            catch (PublicApiException e)
            {
                assertEquals(404, e.getHttpResponse().getStatusCode());
            }
            
            // delete item with candidate user
            createItemObject = AlfrescoDefaultObjectMapper.createObjectNode();
            createItemObject.put("id", docNodeRefs[0].getId());
            result = tasksClient.addTaskItem(task.getId(), AlfrescoDefaultObjectMapper.writeValueAsString(createItemObject));
            
            assertNotNull(result);
            assertEquals(docNodeRefs[0].getId(), result.get("id").textValue());
            
            itemJSON = tasksClient.findTaskItem(task.getId(), docNodeRefs[0].getId());
            assertEquals(docNodeRefs[0].getId(), itemJSON.get("id").textValue());
            
            activitiProcessEngine.getTaskService().addCandidateUser(task.getId(), otherPerson);
            publicApiClient.setRequestContext(otherContext);
            tasksClient.deleteTaskItem(task.getId(), docNodeRefs[0].getId());
            try
            {
                tasksClient.findTaskItem(task.getId(), docNodeRefs[0].getId());
                fail("Expected exception");
            }
            catch (PublicApiException e)
            {
                assertEquals(404, e.getHttpResponse().getStatusCode());
            }
            
            // delete item with not involved user
            createItemObject = AlfrescoDefaultObjectMapper.createObjectNode();
            createItemObject.put("id", docNodeRefs[0].getId());
            result = tasksClient.addTaskItem(task.getId(), AlfrescoDefaultObjectMapper.writeValueAsString(createItemObject));
            
            assertNotNull(result);
            assertEquals(docNodeRefs[0].getId(), result.get("id").textValue());
            
            itemJSON = tasksClient.findTaskItem(task.getId(), docNodeRefs[0].getId());
            assertEquals(docNodeRefs[0].getId(), itemJSON.get("id").textValue());
            
            activitiProcessEngine.getTaskService().deleteCandidateUser(task.getId(), otherPerson);
            publicApiClient.setRequestContext(otherContext);
            try
            {
                tasksClient.deleteTaskItem(task.getId(), docNodeRefs[0].getId());
                fail("Expected exception");
            }
            catch (PublicApiException e)
            {
                assertEquals(403, e.getHttpResponse().getStatusCode());
            }
            
            // delete item with user from candidate group with no assignee
            List<MemberOfSite> memberships = getTestFixture().getNetwork(otherContext.getNetworkId()).getSiteMemberships(otherContext.getRunAsUser());
            assertTrue(memberships.size() > 0);
            MemberOfSite memberOfSite = memberships.get(0);
            String group = "GROUP_site_" + memberOfSite.getSiteId() + "_" + memberOfSite.getRole().name();
            
            activitiProcessEngine.getTaskService().deleteCandidateUser(task.getId(), otherContext.getRunAsUser());
            activitiProcessEngine.getTaskService().addCandidateGroup(task.getId(), group);
            publicApiClient.setRequestContext(otherContext);
            createItemObject = AlfrescoDefaultObjectMapper.createObjectNode();
            createItemObject.put("id", docNodeRefs[0].getId());
            result = tasksClient.addTaskItem(task.getId(), AlfrescoDefaultObjectMapper.writeValueAsString(createItemObject));
            
            assertNotNull(result);
            assertEquals(docNodeRefs[0].getId(), result.get("id").textValue());
            
            itemJSON = tasksClient.findTaskItem(task.getId(), docNodeRefs[0].getId());
            assertEquals(docNodeRefs[0].getId(), itemJSON.get("id").textValue());
            
            tasksClient.deleteTaskItem(task.getId(), docNodeRefs[0].getId());
            try
            {
                tasksClient.findTaskItem(task.getId(), docNodeRefs[0].getId());
                fail("Expected exception");
            }
            catch (PublicApiException e)
            {
                assertEquals(404, e.getHttpResponse().getStatusCode());
            }
            
            // delete item with user from candidate group with assignee
            activitiProcessEngine.getTaskService().setAssignee(task.getId(), requestContext.getRunAsUser());
            publicApiClient.setRequestContext(requestContext);
            createItemObject = AlfrescoDefaultObjectMapper.createObjectNode();
            createItemObject.put("id", docNodeRefs[0].getId());
            result = tasksClient.addTaskItem(task.getId(), AlfrescoDefaultObjectMapper.writeValueAsString(createItemObject));
            
            assertNotNull(result);
            assertEquals(docNodeRefs[0].getId(), result.get("id").textValue());
            
            itemJSON = tasksClient.findTaskItem(task.getId(), docNodeRefs[0].getId());
            assertEquals(docNodeRefs[0].getId(), itemJSON.get("id").textValue());
            
            publicApiClient.setRequestContext(otherContext);
            try
            {
                tasksClient.deleteTaskItem(task.getId(), docNodeRefs[0].getId());
                fail("Expected exception");
            }
            catch (PublicApiException e)
            {
                assertEquals(403, e.getHttpResponse().getStatusCode());
            }
            
            publicApiClient.setRequestContext(requestContext);
            itemJSON = tasksClient.findTaskItem(task.getId(), docNodeRefs[0].getId());
            assertEquals(docNodeRefs[0].getId(), itemJSON.get("id").textValue());
            tasksClient.deleteTaskItem(task.getId(), docNodeRefs[0].getId());
            try
            {
                tasksClient.findTaskItem(task.getId(), docNodeRefs[0].getId());
                fail("Expected exception");
            }
            catch (PublicApiException e)
            {
                assertEquals(404, e.getHttpResponse().getStatusCode());
            }
            
            // invalid task id
            publicApiClient.setRequestContext(requestContext);
            try
            {
                tasksClient.deleteTaskItem("fakeid", docNodeRefs[0].getId());
                fail("Expected exception");
            }
            catch (PublicApiException e)
            {
                assertEquals(404, e.getHttpResponse().getStatusCode());
            }
            
            // invalid item id
            try
            {
                tasksClient.deleteTaskItem(task.getId(), "fakeid");
                fail("Expected exception");
            }
            catch (PublicApiException e)
            {
                assertEquals(404, e.getHttpResponse().getStatusCode());
            }
            
            // delete item from completed task
            createItemObject = AlfrescoDefaultObjectMapper.createObjectNode();
            createItemObject.put("id", docNodeRefs[0].getId());
            result = tasksClient.addTaskItem(task.getId(), AlfrescoDefaultObjectMapper.writeValueAsString(createItemObject));
            
            assertNotNull(result);
            assertEquals(docNodeRefs[0].getId(), result.get("id").textValue());
            
            itemJSON = tasksClient.findTaskItem(task.getId(), docNodeRefs[0].getId());
            assertEquals(docNodeRefs[0].getId(), itemJSON.get("id").textValue());
            
            TenantUtil.runAsUserTenant(new TenantRunAsWork<Void>()
            {
                @Override
                public Void doWork() throws Exception
                {
                    activitiProcessEngine.getTaskService().complete(task.getId());
                    return null;
                }
            }, requestContext.getRunAsUser(), requestContext.getNetworkId());
            
            try
            {
                tasksClient.deleteTaskItem(task.getId(), docNodeRefs[0].getId());
                fail("Expected exception");
            }
            catch (PublicApiException e)
            {
                assertEquals(404, e.getHttpResponse().getStatusCode());
            }
        }
        finally
        {
            cleanupProcessInstance(processInfo.getId());
        }
    }
    
    protected ProcessInstance startAdhocProcess(final String user, final String networkId, final String businessKey)
    {
        return TenantUtil.runAsUserTenant(new TenantRunAsWork<ProcessInstance>()
        {
            @Override
            public ProcessInstance doWork() throws Exception
            {
                String processDefinitionKey = "@" + networkId + "@activitiAdhoc";
                // Set required variables for adhoc process and start
                Map<String, Object> variables = new HashMap<String, Object>();
                ActivitiScriptNode person = getPersonNodeRef(user);
                variables.put("bpm_assignee", person);
                variables.put("wf_notifyMe", Boolean.FALSE);
                variables.put(WorkflowConstants.PROP_INITIATOR, person);
                return activitiProcessEngine.getRuntimeService().startProcessInstanceByKey(processDefinitionKey, businessKey, variables);
            }
        }, user, networkId);
    }
    
    protected int getResultSizeForTaskQuery(Map<String, String> params, TasksClient tasksClient) throws Exception 
    {
        JsonNode taskListJSONObject = tasksClient.findTasks(params);
        assertNotNull(taskListJSONObject);
        ArrayNode jsonEntries = (ArrayNode) taskListJSONObject.get("entries");
        return jsonEntries.size();
    }
    
    protected void assertTasksPresentInTaskQuery(Map<String, String> params, TasksClient tasksClient, String ...taskIds) throws Exception 
    {
       assertTasksPresentInTaskQuery(params, tasksClient, true, taskIds);
    }
    
    protected void assertTasksPresentInTaskQuery(Map<String, String> params, TasksClient tasksClient, boolean countShouldMatch, String ...taskIds) throws Exception 
    {
        JsonNode taskListJSONObject = tasksClient.findTasks(params);
        assertNotNull(taskListJSONObject);
        ArrayNode jsonEntries = (ArrayNode) taskListJSONObject.get("entries");
        if(countShouldMatch) {
            assertEquals("Wrong amount of tasks returned by query", taskIds.length, jsonEntries.size());
        }
        
        List<String> tasksToFind = new ArrayList<String>();
        tasksToFind.addAll(Arrays.asList(taskIds));
        
        for(int i=0; i< jsonEntries.size(); i++) 
        {
            JsonNode entry = jsonEntries.get(i).get("entry");
            assertNotNull(entry);
            String taskId = entry.get("id").textValue();
            tasksToFind.remove(taskId);
        }
        assertEquals("Not all tasks have been found in query response, missing: " + StringUtils.join(tasksToFind, ","), 
                    0, tasksToFind.size());
    }
    
    protected void cleanupProcessInstance(ProcessInstance... processInstances)
    {
        // Clean up process-instance regardless of test success/failure
        try 
        {
            for(ProcessInstance processInstance : processInstances)
            {
                if(processInstance != null)
                {
                    activitiProcessEngine.getRuntimeService().deleteProcessInstance(processInstance.getId(), null);
                    activitiProcessEngine.getHistoryService().deleteHistoricProcessInstance(processInstance.getId());
                }
            }
        }
        catch(Throwable t)
        {
            // Ignore error during cleanup to prevent swallowing potential assetion-exception
            log("Error while cleaning up process instance", t);
        }
    }
    
    protected void cleanupProcessInstance(String... processInstances)
    {
        // Clean up process-instance regardless of test success/failure
        try 
        {
            for (String instanceId : processInstances)
            {
                activitiProcessEngine.getRuntimeService().deleteProcessInstance(instanceId, null);
                activitiProcessEngine.getHistoryService().deleteHistoricProcessInstance(instanceId);
            }
        }
        catch(Throwable t)
        {
            // Ignore error during cleanup to prevent swallowing potential assetion-exception
            log("Error while cleaning up process instance", t);
        }
    }
}
